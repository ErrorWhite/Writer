import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createReadStream, createWriteStream } from 'node:fs'
import { copyFile, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pipeline } from 'node:stream/promises'
import { Readable } from 'node:stream'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const root = fileURLToPath(new URL('..', import.meta.url))
const nodeVersion = '22.23.5'
const webViewVersion = '1.0.4370.26'
const outputDirectory = resolve(process.argv[2] || root)

function run(program, args) {
  const result = spawnSync(program, args, { cwd: root, stdio: 'inherit', windowsHide: true })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${program} exited with status ${result.status}`)
}

async function sha256(file) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}

async function getDependency(cache, stage, name, url, expected) {
  const cached = join(cache, name)
  if (expected ? await sha256(cached).catch(() => '') === expected : await readFile(cached).then((file) => file.length > 0, () => false)) return cached
  const download = join(stage, name)
  const response = await fetch(url, { signal: AbortSignal.timeout(20 * 60 * 1000) })
  if (!response.ok || !response.body) throw new Error(`Download failed for ${name} (HTTP ${response.status})`)
  console.log(`Downloading ${name}…`)
  await pipeline(Readable.fromWeb(response.body), createWriteStream(download))
  if (expected && await sha256(download) !== expected) throw new Error(`Checksum mismatch for ${name}`)
  await copyFile(download, cached)
  return cached
}

async function pack() {
  if (process.platform !== 'win32') throw new Error('Run this packaging command on Windows.')
  const { version } = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'))
  const temporary = await realpath(tmpdir())
  const cache = join(temporary, 'qingjian-webview-runtime')
  await mkdir(cache, { recursive: true })
  const stage = await mkdtemp(join(temporary, 'qingjian-lite-package-'))
  try {
    const [node, sdk, nodeLicense] = await Promise.all([
      getDependency(cache, stage, `verified-node-v${nodeVersion}-win-x64.exe`,
        `https://nodejs.org/dist/v${nodeVersion}/win-x64/node.exe`, '247e9693d316da9857e44990e5e11709f596f18112251b8026ec6eed80bdf38a'),
      getDependency(cache, stage, `webview2-${webViewVersion}.zip`,
        `https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/${webViewVersion}/microsoft.web.webview2.${webViewVersion}.nupkg`,
        '1c0dacac84b70109b3fd42f90c53a0ba942abc3a87590d7a4e0ccd86e4a06cf4'),
      getDependency(cache, stage, `node-v${nodeVersion}-LICENSE.txt`, `https://raw.githubusercontent.com/nodejs/node/v${nodeVersion}/LICENSE`),
    ])
    const sdkDir = join(stage, 'sdk')
    await mkdir(sdkDir)
    run('tar.exe', ['-xf', sdk, '-C', sdkDir])
    const folder = 'Qingjian'
    const bundle = join(stage, folder)
    const appRoot = join(bundle, 'app')
    const runtime = join(bundle, 'runtime')
    await mkdir(join(appRoot, 'server'), { recursive: true })
    await mkdir(join(appRoot, 'src'))
    await mkdir(runtime)
    await copyFile(node, join(runtime, 'node.exe'))
    await copyFile(nodeLicense, join(runtime, 'LICENSE-node.txt'))
    await copyFile(join(sdkDir, 'LICENSE.txt'), join(bundle, 'LICENSE-WebView2.txt'))
    await copyFile(join(sdkDir, 'runtimes', 'win-x64', 'native', 'WebView2Loader.dll'), join(bundle, 'WebView2Loader.dll'))
    const sdkRefs = ['Microsoft.Web.WebView2.Core.dll', 'Microsoft.Web.WebView2.WinForms.dll']
    for (const name of sdkRefs) await copyFile(join(sdkDir, 'lib', 'net462', name), join(bundle, name))

    const csc = join(process.env.WINDIR, 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe')
    run(csc, ['/nologo', '/target:winexe', '/platform:x64', '/optimize+', '/codepage:65001',
      '/out:' + join(bundle, 'Qingjian.exe'), '/reference:System.Net.Http.dll', '/reference:System.Windows.Forms.dll',
      '/reference:System.Drawing.dll', ...sdkRefs.map(name => '/reference:' + join(bundle, name)), join(root, 'server', 'DesktopWindow.cs')])
    await writeFile(join(appRoot, 'package.json'), JSON.stringify({ name: 'qingjian-desktop', version, private: true, type: 'module' }))
    await build({
      entryPoints: [join(root, 'server', 'desktop-service.js')], outfile: join(appRoot, 'server', 'desktop-service.js'),
      bundle: true, platform: 'node', target: 'node22', format: 'esm',
      banner: { js: "import { createRequire as createBundleRequire } from 'node:module'; const require = createBundleRequire(import.meta.url);" },
    })
    run(process.execPath, ['--check', join(appRoot, 'server', 'desktop-service.js')])
    await copyFile(join(root, 'orison', 'LICENSE'), join(bundle, 'LICENSE-OrisonSpace.txt'))
    await copyFile(join(root, 'node_modules', '@jitl', 'quickjs-singlefile-cjs-release-sync', 'LICENSE'), join(bundle, 'LICENSE-QuickJS.txt'))
    await copyFile(join(root, 'orison-attribution.txt'), join(bundle, 'NOTICE.txt'))
    run(process.execPath, [join(root, 'node_modules', 'vite', 'bin', 'vite.js'), 'build', '--configLoader', 'runner', '--outDir', join(appRoot, 'dist')])
    await writeFile(join(bundle, 'README.txt'), '\uFEFF青简 · Windows 轻量版\r\n\r\n解压整个文件夹，运行 Qingjian.exe。需要 Microsoft Edge WebView2 Runtime。\r\n书稿、还原点及 AI 配置沿用 %APPDATA%\\Qingjian\\books。界面偏好保存在 WebView2 中。\r\n支持通过书架的「导入全书 JSON」导入网页版备份，同名覆盖前会建立还原点。\r\n')
    const filename = `Qingjian-${version}-Lite-Windows-x64.zip`
    const zip = join(stage, filename)
    run('tar.exe', ['-a', '-cf', zip, '-C', stage, folder])
    const output = join(outputDirectory, filename)
    await pipeline(createReadStream(zip), createWriteStream(output))
    console.log(output)
    console.log(`SHA256 ${await sha256(output)}`)
  } finally {
    await rm(stage, { recursive: true, force: true })
  }
}

await pack()
