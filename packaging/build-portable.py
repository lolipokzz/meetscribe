#!/usr/bin/env python3
"""Assemble a Windows x64 ZIP from explicitly supplied local inputs; no network.
Requires :app:installDist -PjavafxPlatform=win to have run after final app edits.
ZIP entries have stable timestamps/order; manifest hashes pin every payload file.
"""
import argparse
import hashlib
import json
import re
import pathlib
import shutil
import zipfile


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jre', 'capture', 'whisper', 'model', 'licenses', 'sources'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    parser.add_argument('--llama', type=pathlib.Path)
    parser.add_argument('--version', default='0.9.0-beta.1')
    parser.add_argument('--root', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.root.resolve()
    lib = root / 'app/build/install/app/lib'
    jars = sorted(lib.glob('*.jar'))
    if not jars or not all(any(j.name.startswith('javafx-' + part + '-') and j.name.endswith('-win.jar') for j in jars) for part in ('base', 'graphics', 'controls')):
        raise SystemExit('Build Windows JavaFX distribution first: ./gradlew :app:installDist -PjavafxPlatform=win')
    if any(j.name.startswith('javafx-') and not j.name.endswith('-win.jar') for j in jars):
        raise SystemExit('Non-Windows JavaFX jar in distribution')
    if not (args.jre / 'bin/javaw.exe').is_file():
        raise SystemExit('JRE must be an extracted Windows runtime')
    for binary in [args.capture, args.whisper] + ([args.llama] if args.llama else []):
        with binary.open('rb') as stream:
            if stream.read(2) != b'MZ':
                raise SystemExit('Expected a Windows PE executable: ' + str(binary))
    source_data = json.loads(args.sources.read_text())
    for component, binary in [('capture', args.capture), ('whisper-cli', args.whisper)] + ([('llama-completion', args.llama)] if args.llama else []):
        entry = next((item for item in source_data if item.get('component') == component), None)
        if not entry or sha(binary) != entry['sha256']:
            raise SystemExit('Native binary does not match sources manifest: ' + component)
    # Model identity is explicit and pinned by the preparer, never accepted on name alone.
    model_entry = next((entry for entry in source_data if entry.get('component') == 'whisper-model'), None)
    if not model_entry or sha(args.model) != model_entry['sha256']:
        raise SystemExit('Model does not match sources manifest')
    output = root / 'build/releases'
    output.mkdir(parents=True, exist_ok=True)
    name = 'MeetScribe-' + args.version + '-windows-x64'
    stage = output / name
    if stage.exists():
        shutil.rmtree(stage)
    (stage / 'app/lib').mkdir(parents=True)
    for jar in jars:
        shutil.copy2(jar, stage / 'app/lib' / jar.name)
    shutil.copytree(args.jre, stage / 'runtime')
    shutil.copytree(args.licenses, stage / 'licenses')
    for binary, dest in [(args.capture, 'capture/meetscribe-capture.exe'), (args.whisper, 'whisper/whisper-cli.exe')] + ([(args.llama, 'llama/llama-completion.exe')] if args.llama else []):
        target = stage / 'native' / dest
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(binary, target)
        for dll in sorted(binary.parent.glob('*.dll')):
            shutil.copy2(dll, target.parent / dll.name)
    (stage / 'models').mkdir()
    shutil.copy2(args.model, stage / 'models/whisper.bin')
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for item in archive.namelist():
                basename = pathlib.PurePosixPath(item).name.upper()
                if not item.endswith('/') and (basename.startswith(('LICENSE', 'NOTICE', 'COPYING')) or '/legal/' in item):
                    destination = stage / 'licenses/jars' / jar.name / pathlib.PurePosixPath(item)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(item))
    # Keep scripts ASCII; paths containing non-ASCII characters expand through cmd's Unicode environment.
    properties = ' '.join('"-Dmeetscribe.' + key + '=%~dp0' + value + '"' for key, value in [
        ('capture', 'native\\capture\\meetscribe-capture.exe'),
        ('whisper', 'native\\whisper\\whisper-cli.exe'),
        ('sttModel', 'models\\whisper.bin')]
        + ([('llama', 'native\\llama\\llama-completion.exe')] if args.llama else []))
    for script, java in [('MeetScribe.cmd', 'javaw'), ('start-debug.cmd', 'java')]:
        content = ('@echo off\nsetlocal DisableDelayedExpansion\n"%~dp0runtime\\bin\\' + java + '.exe" ' + properties
                   + ' -cp "%~dp0app\\lib\\*" io.meetscribe.app.Launcher %*\nexit /b %errorlevel%\n')
        (stage / script).write_bytes(content.replace('\n', '\r\n').encode('ascii'))
    for filename in ('PORTABLE-README.txt', 'THIRD_PARTY_NOTICES.txt'):
        shutil.copy2(root / 'packaging' / filename, stage / filename)
    (stage / 'sources.json').write_text(json.dumps(source_data, indent=2) + '\n')
    for jar in jars:
        match = re.fullmatch(r'(.*?)-(\d[^/]*?)(-win)?\.jar', jar.name)
        artifact, version, classifier = match.groups()
        group = ('org.openjfx' if artifact.startswith('javafx-') else
                 'com.fasterxml.jackson.dataformat' if artifact == 'jackson-dataformat-yaml' else
                 'com.fasterxml.jackson.core' if artifact.startswith('jackson-') else
                 'net.java.dev.jna' if artifact.startswith('jna') else
                 'org.yaml' if artifact == 'snakeyaml' else None)
        url = ('https://repo.maven.apache.org/maven2/' + group.replace('.', '/') + '/' + artifact + '/' + version + '/' + jar.name) if group else 'local project source'
        source_data.append({'component': jar.name, 'version': version, 'url': url, 'sha256': sha(jar), 'source': 'Gradle runtimeClasspath; upstream notices in licenses/jars and licenses/'})
    (stage / 'sources.json').write_text(json.dumps(source_data, indent=2) + '\n')
    hashes = {str(file.relative_to(stage)).replace('\\', '/'): sha(file) for file in sorted(stage.rglob('*')) if file.is_file()}
    (stage / 'SHA256SUMS.txt').write_text(''.join(digest + '  ' + name + '\n' for name, digest in hashes.items()))
    archive_path = output / (name + '.zip')
    with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in sorted(stage.rglob('*')):
            if file.is_file():
                info = zipfile.ZipInfo(name + '/' + file.relative_to(stage).as_posix(), (2026, 10, 5, 0, 0, 0))
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with file.open('rb') as source, archive.open(info, 'w', force_zip64=True) as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
    digest = sha(archive_path)
    (output / (name + '.zip.sha256')).write_text(digest + '  ' + archive_path.name + '\n')
    with zipfile.ZipFile(archive_path) as archive:
        if archive.testzip() is not None:
            raise SystemExit('ZIP verification failed')
    print(json.dumps({'zip': str(archive_path), 'bytes': archive_path.stat().st_size, 'sha256': digest, 'files': len(hashes) + 1}, indent=2))


if __name__ == '__main__':
    main()
