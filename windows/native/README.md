# כספת – Windows Native

The Windows client is being ported to a native Win32 executable.

- No WPF, .NET, WebView, or external runtime installation is required.
- Cryptography uses Windows CNG (BCrypt).
- Credential JSON, encrypted file streams, encrypted index, and VLTBACK1 backups keep the Android-compatible format.
- Third-party source and notices are in third_party/README.md.
- The executable statically links the MSVC runtime; it uses only Windows system DLLs at runtime.
