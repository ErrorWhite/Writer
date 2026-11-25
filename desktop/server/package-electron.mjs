import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createReadStream, createWriteStream } from 'node:fs'
import { copyFile, mkdir, mkdtemp, readFile, realpath, rename, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const root = fileURLToPath(new URL('..', import.meta.url))
const version = '45.4.1'
const runtime = `electron-v${version}-win32-x64.zip`
const checksum = '2ba6223afa9121f42831fefe92c434334907540165294fc56697449d12e7cfe9'
const outputDir = resolve(process.argv[2] || root)

function run(program, args, env = process.env) {
  const result = spawnSync(program, args, { cwd: root, env, stdio: 'inherit', windowsHide: true })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${program} exited with status ${result.status}`)
}

async function sha256(file) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}

async function getRuntime(temporary, stage) {
  const cache = join(temporary, `qingjian-electron-${version}`)
  const cached = join(cache, 'verified-' + runtime)
  if (await sha256(cached).catch(() => '') === checksum) return cached
  const url = `https://github.com/electron/electron/releases/download/v${version}/${runtime}`
  const response = await fetch(url, { signal: AbortSignal.timeout(30 * 60 * 1000) })
  if (!response.ok || !response.body) throw new Error(`Electron download failed (HTTP ${response.status})`)
  const download = join(stage, runtime)
  await pipeline(Readable.fromWeb(response.body), createWriteStream(download))
  if (await sha256(download) !== checksum) throw new Error('Electron runtime checksum mismatch.')
  await mkdir(cache, { recursive: true })
  await copyFile(download, cached)
  return cached
}

async function pack() {
  if (process.platform !== 'win32') throw new Error('Run this packaging command on Windows.')
  const { version: appVersion } = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'))
  const temporary = await realpath(tmpdir())
  await mkdir(outputDir, { recursive: true })
  const stage = await mkdtemp(join(outputDir, '.qingjian-package-'))
  try {
    const folder = 'Qingjian'
    const bundle = join(stage, folder)
    const appRoot = join(bundle, 'resources', 'app')
    await mkdir(bundle)
    run('tar.exe', ['-xf', await getRuntime(temporary, stage), '-C', bundle])
    const exe = join(bundle, 'Qingjian.exe')
    await rename(join(bundle, 'electron.exe'), exe)
    const originalSize = (await stat(exe)).size
    await rename(join(bundle, 'electron_wer.dll'), join(bundle, 'Qingjian_wer.dll'))
    await rm(join(bundle, 'resources', 'default_app.asar'), { force: true })
    await mkdir(join(appRoot, 'server'), { recursive: true })
    await writeFile(join(appRoot, 'package.json'), JSON.stringify({
      name: 'Qingjian', version: appVersion, private: true, type: 'module', main: 'server/desktop.js',
    }))
    await build({
      entryPoints: [join(root, 'server', 'desktop.js')], outfile: join(appRoot, 'server', 'desktop.js'),
      bundle: true, platform: 'node', target: 'node22', format: 'esm', external: ['electron'],
      banner: { js: "import { createRequire as createBundleRequire } from 'node:module'; const require = createBundleRequire(import.meta.url);" },
    })
    run(process.execPath, ['--check', join(appRoot, 'server', 'desktop.js')])
    await copyFile(join(root, 'server', 'qingjian.ico'), join(appRoot, 'server', 'qingjian.ico'))
    await copyFile(join(root, 'orison', 'LICENSE'), join(bundle, 'LICENSE-OrisonSpace.txt'))
    await copyFile(join(root, 'node_modules', '@jitl', 'quickjs-singlefile-cjs-release-sync', 'LICENSE'), join(bundle, 'LICENSE-QuickJS.txt'))
    await copyFile(join(root, 'orison-attribution.txt'), join(bundle, 'NOTICE.txt'))
    const csc = join(process.env.WINDIR, 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe')
    const setIcon = join(stage, 'SetWindowsIcon.exe')
    run(csc, ['/nologo', '/out:' + setIcon, join(root, 'server', 'SetWindowsIcon.cs')])
    run(setIcon, [exe, join(appRoot, 'server', 'qingjian.ico')], { ...process.env, TEMP: stage, TMP: stage })
    if ((await stat(exe)).size < originalSize - 1024 * 1024) throw new Error('Executable was truncated while setting its icon.')
    run(process.execPath, [join(root, 'node_modules', 'vite', 'bin', 'vite.js'), 'build', '--configLoader', 'runner', '--outDir', join(appRoot, 'dist')])
    await writeFile(join(bundle, 'README.txt'), '\uFEFF青简 · 小说写作工作台\r\n\r\n解压整个文件夹，再运行 Qingjian.exe。\r\n关闭窗口会收起到系统托盘。双击托盘图标恢复，右键选择「退出青简」可以结束程序。\r\n书稿和还原点保存在 %APPDATA%\\Qingjian\\books。\r\n')
    const filename = `Qingjian-${appVersion}-Windows-x64.zip`
    const archive = join(stage, filename)
    run('tar.exe', ['-a', '-cf', archive, '-C', stage, folder])
    const output = join(outputDir, filename)
    await rename(archive, output)
    console.log(output)
    console.log(`SHA256 ${await sha256(output)}`)
  } finally {
    await rm(stage, { recursive: true, force: true })
  }
}

await pack()
