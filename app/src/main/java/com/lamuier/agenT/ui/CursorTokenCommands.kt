package com.lamuier.agenT.ui

/**
 * 帮助页复制到 Windows 的提取命令。
 * Cursor 3.27 起，cursorAuth/accessToken 以 cursor-safe-storage:v1: 加密落盘。
 * 命令用当前 Windows 用户解开信封，再把 JWT 放进剪贴板。
 */
internal val cursorTokenPowerShellCommand = """
${'$'}ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security
if (-not ('CursorTokenUnseal' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class CursorTokenUnseal {
    [StructLayout(LayoutKind.Sequential)]
    struct AuthInfo {
        public uint cbSize;
        public uint dwInfoVersion;
        public IntPtr pbNonce;
        public uint cbNonce;
        public IntPtr pbAuthData;
        public uint cbAuthData;
        public IntPtr pbTag;
        public uint cbTag;
        public IntPtr pbMacContext;
        public uint cbMacContext;
        public uint cbAAD;
        public ulong cbData;
        public uint dwFlags;
    }

    [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
    static extern int BCryptOpenAlgorithmProvider(out IntPtr phAlgorithm, string pszAlgId, string pszImplementation, uint dwFlags);
    [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
    static extern int BCryptSetProperty(IntPtr hObject, string pszProperty, byte[] pbInput, int cbInput, uint dwFlags);
    [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
    static extern int BCryptGenerateSymmetricKey(IntPtr hAlgorithm, out IntPtr phKey, IntPtr pbKeyObject, int cbKeyObject, byte[] pbSecret, int cbSecret, uint dwFlags);
    [DllImport("bcrypt.dll")]
    static extern int BCryptDecrypt(IntPtr hKey, byte[] pbInput, int cbInput, ref AuthInfo pPaddingInfo, IntPtr pbIV, int cbIV, byte[] pbOutput, int cbOutput, out int pcbResult, uint dwFlags);
    [DllImport("bcrypt.dll")]
    static extern int BCryptDestroyKey(IntPtr hKey);
    [DllImport("bcrypt.dll")]
    static extern int BCryptCloseAlgorithmProvider(IntPtr hAlgorithm, uint dwFlags);

    public static byte[] Unseal(byte[] key, byte[] blob) {
        if (blob.Length < 31 || blob[0] != (byte)'v' || blob[1] != (byte)'1' || blob[2] != (byte)'0')
            throw new InvalidOperationException("unsupported ciphertext");
        const int nonceLen = 12;
        const int tagLen = 16;
        byte[] nonce = new byte[nonceLen];
        Buffer.BlockCopy(blob, 3, nonce, 0, nonceLen);
        int cipherLen = blob.Length - 3 - nonceLen - tagLen;
        byte[] cipher = new byte[cipherLen];
        byte[] tag = new byte[tagLen];
        Buffer.BlockCopy(blob, 3 + nonceLen, cipher, 0, cipherLen);
        Buffer.BlockCopy(blob, blob.Length - tagLen, tag, 0, tagLen);
        IntPtr algorithm;
        int status = BCryptOpenAlgorithmProvider(out algorithm, "AES", null, 0);
        if (status != 0) throw new InvalidOperationException("AES open failed");
        try {
            byte[] mode = Encoding.Unicode.GetBytes("ChainingModeGCM\0");
            status = BCryptSetProperty(algorithm, "ChainingMode", mode, mode.Length, 0);
            if (status != 0) throw new InvalidOperationException("GCM mode failed");
            IntPtr hKey;
            status = BCryptGenerateSymmetricKey(algorithm, out hKey, IntPtr.Zero, 0, key, key.Length, 0);
            if (status != 0) throw new InvalidOperationException("key import failed");
            try {
                GCHandle noncePin = GCHandle.Alloc(nonce, GCHandleType.Pinned);
                GCHandle tagPin = GCHandle.Alloc(tag, GCHandleType.Pinned);
                try {
                    AuthInfo info = new AuthInfo();
                    info.cbSize = (uint)Marshal.SizeOf(typeof(AuthInfo));
                    info.dwInfoVersion = 1;
                    info.pbNonce = noncePin.AddrOfPinnedObject();
                    info.cbNonce = (uint)nonce.Length;
                    info.pbTag = tagPin.AddrOfPinnedObject();
                    info.cbTag = (uint)tag.Length;
                    byte[] plain = new byte[cipher.Length];
                    int written;
                    status = BCryptDecrypt(hKey, cipher, cipher.Length, ref info, IntPtr.Zero, 0, plain, plain.Length, out written, 0);
                    if (status != 0) throw new InvalidOperationException("AES-GCM decrypt failed");
                    if (written == plain.Length) return plain;
                    byte[] trimmed = new byte[written];
                    Buffer.BlockCopy(plain, 0, trimmed, 0, written);
                    return trimmed;
                } finally {
                    noncePin.Free();
                    tagPin.Free();
                }
            } finally {
                BCryptDestroyKey(hKey);
            }
        } finally {
            BCryptCloseAlgorithmProvider(algorithm, 0);
        }
    }
}
'@
}

${'$'}db = Join-Path ${'$'}env:APPDATA 'Cursor\User\globalStorage\state.vscdb'
${'$'}stored = & sqlite3 -readonly ${'$'}db "SELECT value FROM ItemTable WHERE key = 'cursorAuth/accessToken';"
if (${'$'}stored -is [Array]) { ${'$'}stored = ${'$'}stored -join '' }
${'$'}stored = "${'$'}stored".Trim()
if (-not ${'$'}stored) { throw 'cursorAuth/accessToken is missing' }
if (${'$'}stored.StartsWith('"')) { ${'$'}stored = ConvertFrom-Json -InputObject ${'$'}stored }
${'$'}prefix = 'cursor-safe-storage:v1:'
if (${'$'}stored.StartsWith(${'$'}prefix)) {
    ${'$'}statePath = Join-Path ${'$'}env:APPDATA 'Cursor\Local State'
    ${'$'}state = [IO.File]::ReadAllText(${'$'}statePath) | ConvertFrom-Json
    ${'$'}encryptedKey = [Convert]::FromBase64String([string]${'$'}state.os_crypt.encrypted_key)
    if (${'$'}encryptedKey.Length -lt 6 -or [Text.Encoding]::ASCII.GetString(${'$'}encryptedKey, 0, 5) -ne 'DPAPI') {
        throw 'unexpected os_crypt prefix'
    }
    ${'$'}protected = New-Object byte[] (${'$'}encryptedKey.Length - 5)
    [Buffer]::BlockCopy(${'$'}encryptedKey, 5, ${'$'}protected, 0, ${'$'}protected.Length)
    ${'$'}key = [Security.Cryptography.ProtectedData]::Unprotect(
        ${'$'}protected, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    ${'$'}blob = [Convert]::FromBase64String(${'$'}stored.Substring(${'$'}prefix.Length))
    ${'$'}token = [Text.Encoding]::UTF8.GetString([CursorTokenUnseal]::Unseal(${'$'}key, ${'$'}blob)).Trim()
} else {
    ${'$'}token = ${'$'}stored.Trim()
}
if (${'$'}token -notmatch '^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+${'$'}') {
    throw 'result is not a JWT'
}
Set-Clipboard -Value ${'$'}token
Write-Output 'Copied the decrypted Access Token to the clipboard.'
""".trimIndent()

internal val cursorTokenPythonCommand = """
@'
import base64
import ctypes
import json
import os
import sqlite3
import subprocess
import sys
from ctypes import wintypes
from pathlib import Path

PREFIX = "cursor-safe-storage:v1:"


class DATA_BLOB(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


class AUTH(ctypes.Structure):
    _fields_ = [
        ("cbSize", ctypes.c_ulong),
        ("dwInfoVersion", ctypes.c_ulong),
        ("pbNonce", ctypes.POINTER(ctypes.c_ubyte)),
        ("cbNonce", ctypes.c_ulong),
        ("pbAuthData", ctypes.POINTER(ctypes.c_ubyte)),
        ("cbAuthData", ctypes.c_ulong),
        ("pbTag", ctypes.POINTER(ctypes.c_ubyte)),
        ("cbTag", ctypes.c_ulong),
        ("pbMacContext", ctypes.POINTER(ctypes.c_ubyte)),
        ("cbMacContext", ctypes.c_ulong),
        ("cbAAD", ctypes.c_ulong),
        ("cbData", ctypes.c_ulonglong),
        ("dwFlags", ctypes.c_ulong),
    ]


def dpapi_unprotect(data: bytes) -> bytes:
    crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    crypt32.CryptUnprotectData.argtypes = [
        ctypes.POINTER(DATA_BLOB),
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
        wintypes.DWORD,
        ctypes.POINTER(DATA_BLOB),
    ]
    crypt32.CryptUnprotectData.restype = wintypes.BOOL
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.LocalFree.restype = ctypes.c_void_p
    buffer = ctypes.create_string_buffer(data)
    incoming = DATA_BLOB(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char)))
    outgoing = DATA_BLOB()
    if not crypt32.CryptUnprotectData(
        ctypes.byref(incoming), None, None, None, None, 0, ctypes.byref(outgoing)
    ):
        raise OSError(ctypes.get_last_error(), "DPAPI decrypt failed")
    try:
        return ctypes.string_at(outgoing.pbData, outgoing.cbData)
    finally:
        kernel32.LocalFree(outgoing.pbData)


def aes_gcm_decrypt(key: bytes, blob: bytes) -> bytes:
    if not blob.startswith(b"v10") or len(blob) < 3 + 12 + 16:
        raise ValueError("unsupported ciphertext")
    nonce, body = blob[3:15], blob[15:]
    ciphertext, tag = body[:-16], body[-16:]
    bcrypt = ctypes.WinDLL("bcrypt", use_last_error=True)
    status_type = ctypes.c_long
    bcrypt.BCryptOpenAlgorithmProvider.argtypes = [
        ctypes.POINTER(ctypes.c_void_p),
        wintypes.LPCWSTR,
        wintypes.LPCWSTR,
        wintypes.ULONG,
    ]
    bcrypt.BCryptOpenAlgorithmProvider.restype = status_type
    bcrypt.BCryptSetProperty.argtypes = [
        ctypes.c_void_p,
        wintypes.LPCWSTR,
        ctypes.POINTER(ctypes.c_ubyte),
        wintypes.ULONG,
        wintypes.ULONG,
    ]
    bcrypt.BCryptSetProperty.restype = status_type
    bcrypt.BCryptGenerateSymmetricKey.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_void_p),
        ctypes.c_void_p,
        wintypes.ULONG,
        ctypes.POINTER(ctypes.c_ubyte),
        wintypes.ULONG,
        wintypes.ULONG,
    ]
    bcrypt.BCryptGenerateSymmetricKey.restype = status_type
    bcrypt.BCryptDecrypt.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_ubyte),
        wintypes.ULONG,
        ctypes.c_void_p,
        ctypes.c_void_p,
        wintypes.ULONG,
        ctypes.POINTER(ctypes.c_ubyte),
        wintypes.ULONG,
        ctypes.POINTER(wintypes.ULONG),
        wintypes.ULONG,
    ]
    bcrypt.BCryptDecrypt.restype = status_type
    bcrypt.BCryptDestroyKey.argtypes = [ctypes.c_void_p]
    bcrypt.BCryptDestroyKey.restype = status_type
    bcrypt.BCryptCloseAlgorithmProvider.argtypes = [ctypes.c_void_p, wintypes.ULONG]
    bcrypt.BCryptCloseAlgorithmProvider.restype = status_type

    algorithm = ctypes.c_void_p()
    status = bcrypt.BCryptOpenAlgorithmProvider(ctypes.byref(algorithm), "AES", None, 0)
    if status:
        raise OSError(status, "AES open failed")
    mode = ctypes.create_unicode_buffer("ChainingModeGCM")
    status = bcrypt.BCryptSetProperty(
        algorithm,
        "ChainingMode",
        ctypes.cast(mode, ctypes.POINTER(ctypes.c_ubyte)),
        ctypes.sizeof(mode),
        0,
    )
    if status:
        raise OSError(status, "GCM mode failed")
    key_buffer = (ctypes.c_ubyte * len(key)).from_buffer_copy(key)
    handle = ctypes.c_void_p()
    status = bcrypt.BCryptGenerateSymmetricKey(
        algorithm, ctypes.byref(handle), None, 0, key_buffer, len(key), 0
    )
    if status:
        raise OSError(status, "key import failed")
    info = AUTH()
    info.cbSize = ctypes.sizeof(AUTH)
    info.dwInfoVersion = 1
    nonce_buffer = (ctypes.c_ubyte * len(nonce)).from_buffer_copy(nonce)
    tag_buffer = (ctypes.c_ubyte * len(tag)).from_buffer_copy(tag)
    info.pbNonce = ctypes.cast(nonce_buffer, ctypes.POINTER(ctypes.c_ubyte))
    info.cbNonce = len(nonce)
    info.pbTag = ctypes.cast(tag_buffer, ctypes.POINTER(ctypes.c_ubyte))
    info.cbTag = len(tag)
    cipher_buffer = (ctypes.c_ubyte * len(ciphertext)).from_buffer_copy(ciphertext)
    output = (ctypes.c_ubyte * len(ciphertext))()
    written = wintypes.ULONG()
    status = bcrypt.BCryptDecrypt(
        handle,
        cipher_buffer,
        len(ciphertext),
        ctypes.byref(info),
        None,
        0,
        output,
        len(ciphertext),
        ctypes.byref(written),
        0,
    )
    bcrypt.BCryptDestroyKey(handle)
    bcrypt.BCryptCloseAlgorithmProvider(algorithm, 0)
    if status:
        raise OSError(status, "AES-GCM decrypt failed")
    return bytes(output[: written.value])


def os_crypt_key() -> bytes:
    local_state = json.loads(
        (Path(os.environ["APPDATA"]) / "Cursor" / "Local State").read_text(encoding="utf-8")
    )
    raw = base64.b64decode(local_state["os_crypt"]["encrypted_key"])
    if not raw.startswith(b"DPAPI"):
        raise ValueError("unexpected os_crypt prefix")
    return dpapi_unprotect(raw[5:])


def unseal(stored):
    value = stored.strip()
    if value.startswith('"'):
        value = json.loads(value)
    if not value.startswith(PREFIX):
        return value
    blob = base64.b64decode(value[len(PREFIX):])
    return aes_gcm_decrypt(os_crypt_key(), blob).decode("utf-8")


def main() -> None:
    database = Path(os.environ["APPDATA"]) / "Cursor" / "User" / "globalStorage" / "state.vscdb"
    connection = sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)
    row = connection.execute(
        "SELECT value FROM ItemTable WHERE key = ?", ("cursorAuth/accessToken",)
    ).fetchone()
    connection.close()
    if not row or not row[0]:
        raise SystemExit("cursorAuth/accessToken is missing")
    token = unseal(row[0]).strip()
    if token.count(".") != 2 or not token.startswith("eyJ"):
        raise SystemExit("result is not a JWT")
    subprocess.run(["clip.exe"], input=token, text=True, check=True)
    print("Copied the decrypted Access Token to the clipboard.", file=sys.stderr)


if __name__ == "__main__":
    main()
'@ | python -
""".trimIndent()
