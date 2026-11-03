#!/usr/bin/env python3
"""Android-host smoke test of the packaged native engine, without VPN permissions or routing changes.

The fake TUN is an AF_UNIX socket pair. DNS packets go through the native stack and the Java SOCKS fixture.
Run the NativeProxyFixture in mc-linux first (see README).
"""
from pathlib import Path
import hashlib
import json
import shutil
import socket
import struct
import sysconfig
import threading
import time
import zipfile

# The bundled Android Python is statically linked; its sysconfig otherwise names a non-existent libpython.
sysconfig.get_config_vars()['LDLIBRARY'] = None
import ctypes

root = Path(__file__).resolve().parent.parent
fixture = root / 'build/native-fixture.json'
stop = root / 'build/native-fixture.stop'
cache = Path('/data/user/0/dev.mobilecodex.app/cache/nok-native-tests')
cache.mkdir(exist_ok=True)
with zipfile.ZipFile(root / 'vendor/hev-socks5-tunnel-2.19.3.aar') as archive:
    library = cache / 'libhev-socks5-tunnel.so'
    library.write_bytes(archive.read('jni/arm64-v8a/libhev-socks5-tunnel.so'))
lib = ctypes.CDLL(str(library))
lib.hev_socks5_tunnel_main_from_str.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int]
lib.hev_socks5_tunnel_main_from_str.restype = ctypes.c_int
lib.hev_socks5_tunnel_quit.argtypes = []
lib.hev_socks5_tunnel_quit.restype = None

def checksum(data):
    if len(data) % 2: data += b'\0'
    value = sum(struct.unpack('!' + 'H' * (len(data) // 2), data))
    while value >> 16: value = (value & 65535) + (value >> 16)
    return ~value & 65535

def packet(ident):
    query = struct.pack('!HHHHHH', ident, 0x0100, 1, 0, 0, 0) + b'\x03ads\x07example\x00\x00\x01\x00\x01'
    udp = struct.pack('!HHHH', 45678, 53, len(query) + 8, 0) + query
    header = struct.pack('!BBHHHBBH4s4s', 0x45, 0, len(udp) + 20, 1, 0, 64, 17, 0,
                         socket.inet_aton('10.111.0.1'), socket.inet_aton('10.111.0.2'))
    header = header[:10] + struct.pack('!H', checksum(header)) + header[12:]
    return header + udp

try:
    config = json.loads(fixture.read_text())
    yaml = ("tunnel:\n  mtu: 1500\n  ipv4: 10.111.0.1\n  ipv6: 'fd11:111::1'\n"
            "socks5:\n  address: 127.0.0.1\n  port: {port}\n  udp: 'udp'\n"
            "  username: '{username}'\n  password: '{password}'\n"
            "misc:\n  log-file: null\n  log-level: error\n  max-session-count: 32\n").format(**config).encode()
    for cycle in range(3):
        native, client = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
        client.settimeout(5)
        buffer = ctypes.create_string_buffer(yaml)
        result = []
        worker = threading.Thread(target=lambda: result.append(lib.hev_socks5_tunnel_main_from_str(buffer, len(yaml), native.fileno())), daemon=True)
        worker.start()
        try:
            time.sleep(.1)
            ident = 0x1234 + cycle
            client.send(packet(ident))
            response = client.recv(65535)
            ihl = (response[0] & 15) * 4
            assert response[9] == 17 and response[12:16] == socket.inet_aton('10.111.0.2')
            assert struct.unpack_from('!HH', response, ihl) == (53, 45678)
            assert struct.unpack_from('!H', response, ihl + 8)[0] == ident
            assert response[ihl + 11] & 15 == 3
            print('PASS: native IPv4 TUN -> authenticated SOCKS5 UDP -> Java DNS callback -> TUN, cycle', cycle + 1, flush=True)
        finally:
            if worker.is_alive(): lib.hev_socks5_tunnel_quit()
            worker.join(3)
            assert not worker.is_alive(), 'Native stop did not finish'
            assert result == [0], result
            native.close(); client.close()
        print('PASS: native stop and resource release, cycle', cycle + 1, flush=True)
finally:
    stop.touch()
