import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { createReadStream, createWriteStream } from 'node:fs'
import { cp, mkdir, mkdtemp, open, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const source = resolve(process.env.QINGJIAN_ORISON_BUILD_ROOT || join(root, 'orison'))
const shell = join(source, 'apps', 'desktop', 'client', 'shell')
const releaseFilename = 'OrisonSpace-portable-v0.3.0-win-x64.zip'
const checksum = '4fdecff99ea4f93de7d8c4859be60eaa2c30adc9a33e3fe9ddc9080643c34bf8'
const version = '2.0.0'
const output = resolve(process.argv[2] || root, `Qingjian-${version}-Windows-x64.zip`)

function run(executable, args, cwd = root, env = process.env) {
  const result = spawnSync(executable, args, { cwd, env, stdio: 'inherit', windowsHide: true })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`Build command exited with status ${result.status}`)
}
async function hash(file) {
  const state = createHash('sha256')
  for await (const chunk of createReadStream(file)) state.update(chunk)
  return state.digest('hex')
}

async function upstreamRuntime(directory) {
  const cache = join(tmpdir(), 'qingjian-orison-release', releaseFilename)
  if (await hash(cache).catch(() => '') === checksum) return cache
  const url = 'https://github.com/LumenStorm/OrisonSpace/releases/download/v0.3.0/' + releaseFilename
  const response = await fetch(url, { signal: AbortSignal.timeout(15 * 60 * 1000) })
  if (!response.ok || !response.body) throw new Error('Orison runtime download failed: ' + response.status)
  const file = join(directory, releaseFilename)
  await pipeline(Readable.fromWeb(response.body), createWriteStream(file))
  if (await hash(file) !== checksum) throw new Error('Orison runtime checksum mismatch.')
  return file
}

async function verifyWindowsExecutable(file) {
  const handle = await open(file, 'r')
  try {
    const { size } = await handle.stat()
    const header = Buffer.alloc(64)
    await handle.read(header, 0, header.length, 0)
    if (header.toString('ascii', 0, 2) !== 'MZ') throw new Error('Invalid Windows executable')
    const offset = header.readUInt32LE(60)
    const pe = Buffer.alloc(24)
    await handle.read(pe, 0, pe.length, offset)
    if (pe.readUInt32LE() !== 0x4550) throw new Error('Missing Windows PE header')
    const sections = Buffer.alloc(pe.readUInt16LE(6) * 40)
    const sectionOffset = offset + pe.length + pe.readUInt16LE(20)
    const { bytesRead } = await handle.read(sections, 0, sections.length, sectionOffset)
    if (!sections.length || bytesRead !== sections.length) throw new Error('Missing Windows PE sections')
    for (let index = 0; index < sections.length; index += 40) {
      if (sections.readUInt32LE(index + 16) + sections.readUInt32LE(index + 20) > size) {
        throw new Error('Windows executable was truncated while applying the icon')
      }
    }
  } finally {
    await handle.close()
  }
}

const stage = await mkdtemp(join(dirname(output), '.qingjian-orison-package-'))
try {
  const platform = await upstreamRuntime(stage)
  const appDirectory = join(stage, 'Qingjian')
  const resources = join(appDirectory, 'resources')
  const app = join(resources, 'app')
  await mkdir(appDirectory)
  run('tar.exe', ['-xf', platform, '-C', appDirectory])
  const icon = join(root, 'server', 'qingjian.ico')
  const installer = join(stage, 'SetWindowsIcon.exe')
  const csc = join(process.env.WINDIR, 'Microsoft.NET', 'Framework64', 'v4.0.30319', 'csc.exe')
  run(csc, ['/nologo', '/out:' + installer, join(root, 'server', 'SetWindowsIcon.cs')])
  const executable = join(appDirectory, 'Qingjian.exe')
  await rename(join(appDirectory, 'Orison Space.exe'), executable)
  run(installer, [executable, icon], stage, { ...process.env, TEMP: stage, TMP: stage })
  await verifyWindowsExecutable(executable)

  run(process.execPath, [join(shell, 'node_modules', 'electron-vite', 'bin', 'electron-vite.js'), 'build'], shell)
  const asarModule = join(source, 'node_modules', '.pnpm', '@electron+asar@3.4.1', 'node_modules', '@electron', 'asar', 'lib', 'asar.js')
  const { extractAll } = await import(pathToFileURL(asarModule).href)
  const asar = join(resources, 'app.asar')
  extractAll(asar, app)
  await rm(asar)
  await rm(join(resources, 'app.asar.unpacked'), { recursive: true, force: true })
  await cp(join(shell, 'dist'), join(app, 'dist'), { recursive: true, force: true })
  await cp(icon, join(app, 'resources', 'icon.ico'))
  const manifestPath = join(app, 'package.json')
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  await writeFile(manifestPath, JSON.stringify({ ...manifest, name: 'qingjian', productName: '青简', version }, null, 2))
  await cp(join(source, 'LICENSE'), join(appDirectory, 'LICENSE-OrisonSpace.txt'))
  await writeFile(join(appDirectory, 'README.txt'),
    '\uFEFF青简 2.0\r\n基于 Orison Space Apache-2.0 源码。\r\n解压后打开 Qingjian.exe，可在项目页导入旧青简全书 JSON。原书稿不变。\r\n')
  const zip = join(stage, 'Qingjian.zip')
  run('tar.exe', ['-a', '-cf', zip, '-C', stage, 'Qingjian'])
  await rename(zip, output)
  console.log(output, await hash(output))
} finally {
  await rm(stage, { recursive: true, force: true })
}
