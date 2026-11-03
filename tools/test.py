#!/usr/bin/env python3
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / 'build/tests'; out.mkdir(parents=True, exist_ok=True)
sources = [root / ('app/src/main/java/dev/nok/adblock/' + name + '.java') for name in ('DnsMessage', 'DomainRules', 'IpPacket', 'TcpDnsSession', 'TunnelLease', 'TlsHello', 'LocalProxy', 'UpdatePolicy', 'PlainDns')]
tests = [root / 'tests' / name for name in ('CoreTests.java', 'ShutdownTests.java', 'ProxyTests.java', 'IdleProxyTests.java', 'UpdateTests.java')]
subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(out), *map(str, sources + tests)], check=True)
subprocess.run(['java', '-ea', '-cp', str(out), 'dev.nok.adblock.CoreTests', str(root / 'app/src/main/assets/hosts.txt')], check=True)
subprocess.run(['java', '-ea', '-cp', str(out), 'dev.nok.adblock.ShutdownTests'], check=True)
subprocess.run(['java', '-ea', '-cp', str(out), 'dev.nok.adblock.ProxyTests'], check=True)
subprocess.run(['java', '-ea', '-cp', str(out), 'dev.nok.adblock.UpdateTests'], check=True)
subprocess.run(['java', '-ea', '-cp', str(out), 'dev.nok.adblock.IdleProxyTests'], check=True)
