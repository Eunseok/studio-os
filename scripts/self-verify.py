#!/usr/bin/env python3
"""Verify a Studio OS worktree using prepared dependencies, without network or source mutation."""
import json, os, pathlib, shutil, subprocess, sys, tempfile

if len(sys.argv) != 4:
    raise SystemExit('Usage: self-verify.py NODE_MODULES MAVEN_BINARY MAVEN_CACHE')
modules, maven, cache = map(lambda p: pathlib.Path(p).resolve(), sys.argv[1:])
for p in (modules, maven, cache):
    if not p.exists():
        raise SystemExit(f'Prepare dependency cache first: {p}')
source = pathlib.Path.cwd()
ignore = shutil.ignore_patterns('.git', 'data', '.tools', 'node_modules', '.next', 'target', '.env', '.env.*', '__pycache__', 'test-results', 'playwright-report', '*.tsbuildinfo')
with tempfile.TemporaryDirectory(prefix='studio-self-verify-') as directory:
    temp = pathlib.Path(directory)
    checkout = temp / 'source'
    shutil.copytree(source, checkout, ignore=ignore)
    shutil.copytree(cache, temp/'maven-cache')
    (checkout/'frontend/node_modules').symlink_to(modules, target_is_directory=True)
    env = dict(os.environ, NEXT_TELEMETRY_DISABLED='1', TMPDIR=str(temp))
    agents=[p for p in (temp/'maven-cache/net/bytebuddy/byte-buddy-agent').glob('*/*.jar') if p.name==f'byte-buddy-agent-{p.parent.name}.jar']
    if not agents: raise SystemExit('Byte Buddy agent cache is required for sandboxed Mockito tests')
    subprocess.run([str(maven), '-o', '-q', '-Dtest=CapabilityIntegrationTest,ImprovementIntegrationTest', f'-DargLine=-javaagent:{sorted(agents)[-1]}', f'-Dmaven.repo.local={temp / "maven-cache"}', '-f', str(checkout/'backend/pom.xml'), 'test'], check=True, env=env, cwd=checkout)
    subprocess.run(['node', str(modules/'typescript/bin/tsc'), '--noEmit'], check=True, env=env, cwd=checkout/'frontend')
    print('Studio OS self-verification passed: Academy/improvement tests + frontend TypeScript check. Runtime/Workflow sandbox tests require scripts/check.sh outside this nested sandbox. Original worktree untouched.')
