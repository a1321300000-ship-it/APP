using System.Buffers.Binary;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Vault.Windows;

internal sealed class VaultItem
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("mime")] public string Mime { get; set; } = "application/octet-stream";
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("created")] public long Created { get; set; }
}

internal sealed class KeyEnvelope
{
    public byte[] Salt { get; set; } = [];
    public byte[] Wrapped { get; set; } = [];
}

internal sealed class CredentialSet
{
    public KeyEnvelope Pin { get; set; } = new();
    public KeyEnvelope Pattern { get; set; } = new();
}

internal sealed class CredentialDocument
{
    [JsonPropertyName("version")] public int Version { get; set; } = 1;
    [JsonPropertyName("pinSalt")] public string PinSalt { get; set; } = "";
    [JsonPropertyName("pinWrapped")] public string PinWrapped { get; set; } = "";
    [JsonPropertyName("patternSalt")] public string PatternSalt { get; set; } = "";
    [JsonPropertyName("patternWrapped")] public string PatternWrapped { get; set; } = "";
}

internal sealed class FailedAttempt
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("time")] public long Time { get; set; }
    [JsonPropertyName("count")] public int Count { get; set; }
}

internal sealed class SettingsDocument
{
    [JsonPropertyName("scareEnabled")] public bool ScareEnabled { get; set; } = true;
    [JsonPropertyName("scareThreshold")] public int ScareThreshold { get; set; } = 2;
    [JsonPropertyName("consecutiveFailures")] public int ConsecutiveFailures { get; set; }
    [JsonPropertyName("totalFailures")] public int TotalFailures { get; set; }
    [JsonPropertyName("lockoutUntil")] public long LockoutUntil { get; set; }
    [JsonPropertyName("lockoutLevel")] public int LockoutLevel { get; set; }
    [JsonPropertyName("attemptHistory")] public List<FailedAttempt> AttemptHistory { get; set; } = [];
}

internal sealed record FailureRecord(string AttemptId, int Consecutive, int Total);

internal static class VaultCrypto
{
    internal const int ChunkSize = 1024 * 1024;
    private const int StreamBufferSize = 64 * 1024;
    private const int StreamMagic = 0x564C5431;
    private const int TagSize = 16;

    internal static byte[] RandomBytes(int size) => RandomNumberGenerator.GetBytes(size);

    internal static byte[] Derive(string code, byte[] salt) =>
        Rfc2898DeriveBytes.Pbkdf2(code, salt, 150_000, HashAlgorithmName.SHA1, 32);

    internal static byte[] SealBytes(byte[] plain, byte[] key, byte[] aad)
    {
        byte[] nonce = RandomBytes(12);
        byte[] cipher = new byte[plain.Length];
        byte[] tag = new byte[TagSize];
        using (var aes = new AesGcm(key, TagSize))
            aes.Encrypt(nonce, plain, cipher, tag, aad);
        byte[] result = new byte[nonce.Length + cipher.Length + tag.Length];
        Buffer.BlockCopy(nonce, 0, result, 0, nonce.Length);
        Buffer.BlockCopy(cipher, 0, result, nonce.Length, cipher.Length);
        Buffer.BlockCopy(tag, 0, result, nonce.Length + cipher.Length, tag.Length);
        CryptographicOperations.ZeroMemory(cipher);
        return result;
    }

    internal static byte[] OpenBytes(byte[] blob, byte[] key, byte[] aad)
    {
        if (blob.Length < 28) throw new InvalidDataException("נתוני ההצפנה פגומים");
        byte[] nonce = blob.AsSpan(0, 12).ToArray();
        byte[] cipher = blob.AsSpan(12, blob.Length - 28).ToArray();
        byte[] tag = blob.AsSpan(blob.Length - 16, 16).ToArray();
        byte[] plain = new byte[cipher.Length];
        using (var aes = new AesGcm(key, TagSize))
            aes.Decrypt(nonce, cipher, tag, plain, aad);
        CryptographicOperations.ZeroMemory(cipher);
        return plain;
    }

    private static byte[] ChunkAad(string context, long index)
    {
        byte[] prefix = Encoding.UTF8.GetBytes(context);
        byte[] aad = new byte[prefix.Length + 8];
        Buffer.BlockCopy(prefix, 0, aad, 0, prefix.Length);
        BinaryPrimitives.WriteInt64BigEndian(aad.AsSpan(prefix.Length, 8), index);
        return aad;
    }

    internal static long EncryptStream(Stream input, Stream output, byte[] key, string context)
    {
        byte[] buffer = new byte[StreamBufferSize];
        long chunk = 0;
        long total = 0;
        while (true)
        {
            int count = 0;
            while (count < buffer.Length)
            {
                int read = input.Read(buffer, count, buffer.Length - count);
                if (read == 0) break;
                count += read;
            }
            if (count == 0) break;

            byte[] nonce = RandomBytes(12);
            byte[] cipher = new byte[count];
            byte[] tag = new byte[TagSize];
            using (var aes = new AesGcm(key, TagSize))
                aes.Encrypt(nonce, buffer.AsSpan(0, count), cipher, tag, ChunkAad(context, chunk));

            WriteInt32(output, count);
            output.Write(nonce);
            output.Write(cipher);
            output.Write(tag);
            total += count;
            chunk++;
            if (count < buffer.Length) break;
        }
        WriteInt32(output, 0);
        output.Flush();
        CryptographicOperations.ZeroMemory(buffer);
        return total;
    }

    internal static void DecryptStream(Stream input, Stream output, byte[] key, string context)
    {
        if (ReadInt32(input) != StreamMagic) throw new InvalidDataException("קובץ מוצפן לא מזוהה");
        long chunk = 0;
        while (true)
        {
            int plainLength = ReadInt32(input);
            if (plainLength == 0) break;
            if (plainLength < 1 || plainLength > ChunkSize)
                throw new InvalidDataException("מבנה קובץ לא תקין");

            byte[] nonce = ReadExactly(input, 12);
            byte[] cipher = ReadExactly(input, plainLength);
            byte[] tag = ReadExactly(input, TagSize);
            byte[] plain = new byte[plainLength];
            using (var aes = new AesGcm(key, TagSize))
                aes.Decrypt(nonce, cipher, tag, plain, ChunkAad(context, chunk));
            output.Write(plain);
            CryptographicOperations.ZeroMemory(plain);
            CryptographicOperations.ZeroMemory(cipher);
            chunk++;
        }
        output.Flush();
    }

    internal static void WriteInt32(Stream stream, int value)
    {
        Span<byte> bytes = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(bytes, value);
        stream.Write(bytes);
    }

    internal static int ReadInt32(Stream stream)
    {
        byte[] bytes = ReadExactly(stream, 4);
        return BinaryPrimitives.ReadInt32BigEndian(bytes);
    }

    internal static byte[] ReadExactly(Stream stream, int count)
    {
        byte[] bytes = new byte[count];
        stream.ReadExactly(bytes);
        return bytes;
    }
}

internal static class Credentials
{
    private static readonly byte[] MasterAad = Encoding.UTF8.GetBytes("vault-master-key");

    internal static CredentialSet Create(byte[] master, string pin, string pattern) =>
        new() { Pin = Wrap(master, pin), Pattern = Wrap(master, pattern) };

    private static KeyEnvelope Wrap(byte[] master, string code)
    {
        byte[] salt = VaultCrypto.RandomBytes(16);
        byte[] key = VaultCrypto.Derive(code, salt);
        try { return new KeyEnvelope { Salt = salt, Wrapped = VaultCrypto.SealBytes(master, key, MasterAad) }; }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    internal static byte[]? Unlock(CredentialSet set, string code, string type)
    {
        KeyEnvelope envelope = type == "pin" ? set.Pin : set.Pattern;
        byte[] key = VaultCrypto.Derive(code, envelope.Salt);
        try
        {
            byte[] master = VaultCrypto.OpenBytes(envelope.Wrapped, key, MasterAad);
            if (master.Length == 32) return master;
            CryptographicOperations.ZeroMemory(master);
            return null;
        }
        catch (CryptographicException) { return null; }
        catch (InvalidDataException) { return null; }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    internal static byte[]? UnlockAny(CredentialSet set, string code) =>
        Unlock(set, code, "pin") ?? Unlock(set, code, "pattern");

    internal static CredentialSet Replace(CredentialSet set, byte[] master, string code, string type) =>
        type == "pin"
            ? new CredentialSet { Pin = Wrap(master, code), Pattern = set.Pattern }
            : new CredentialSet { Pin = set.Pin, Pattern = Wrap(master, code) };

    internal static byte[] ToJson(CredentialSet set)
    {
        var doc = new CredentialDocument
        {
            PinSalt = Convert.ToBase64String(set.Pin.Salt),
            PinWrapped = Convert.ToBase64String(set.Pin.Wrapped),
            PatternSalt = Convert.ToBase64String(set.Pattern.Salt),
            PatternWrapped = Convert.ToBase64String(set.Pattern.Wrapped)
        };
        return JsonSerializer.SerializeToUtf8Bytes(doc);
    }

    internal static CredentialSet FromJson(byte[] bytes)
    {
        var doc = JsonSerializer.Deserialize<CredentialDocument>(bytes)
                  ?? throw new InvalidDataException("פרטי הגיבוי פגומים");
        if (doc.Version != 1) throw new InvalidDataException("גרסת פרטי הכניסה אינה נתמכת");
        return new CredentialSet
        {
            Pin = new KeyEnvelope { Salt = Convert.FromBase64String(doc.PinSalt), Wrapped = Convert.FromBase64String(doc.PinWrapped) },
            Pattern = new KeyEnvelope { Salt = Convert.FromBase64String(doc.PatternSalt), Wrapped = Convert.FromBase64String(doc.PatternWrapped) }
        };
    }
}

internal sealed class VaultStore
{
    private readonly string baseDir;
    private readonly string dataDir;
    private readonly string indexFile;
    private readonly string credentialsFile;
    private readonly string settingsFile;

    internal VaultStore()
    {
        baseDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "כספת", "vault");
        dataDir = Path.Combine(baseDir, "data");
        indexFile = Path.Combine(baseDir, "index.enc");
        credentialsFile = Path.Combine(baseDir, "credentials.json");
        settingsFile = Path.Combine(baseDir, "settings.json");
        Directory.CreateDirectory(dataDir);
    }

    internal bool HasCredentials() => File.Exists(credentialsFile) && LoadCredentials() != null;
    internal CredentialSet? LoadCredentials()
    {
        try { return File.Exists(credentialsFile) ? Credentials.FromJson(File.ReadAllBytes(credentialsFile)) : null; }
        catch { return null; }
    }

    internal void SaveCredentials(CredentialSet set)
    {
        byte[] bytes = Credentials.ToJson(set);
        WriteAtomic(credentialsFile, bytes);
    }

    internal List<VaultItem> LoadIndex(byte[] master)
    {
        if (!File.Exists(indexFile)) return [];
        byte[] encrypted = File.ReadAllBytes(indexFile);
        byte[] plain = VaultCrypto.OpenBytes(encrypted, master, Encoding.UTF8.GetBytes("vault-index"));
        try { return JsonSerializer.Deserialize<List<VaultItem>>(plain) ?? []; }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }

    internal void SaveIndex(IReadOnlyList<VaultItem> items, byte[] master)
    {
        byte[] plain = JsonSerializer.SerializeToUtf8Bytes(items);
        byte[] encrypted = VaultCrypto.SealBytes(plain, master, Encoding.UTF8.GetBytes("vault-index"));
        try { WriteAtomic(indexFile, encrypted); }
        finally
        {
            CryptographicOperations.ZeroMemory(plain);
            CryptographicOperations.ZeroMemory(encrypted);
        }
    }

    internal VaultItem ImportFile(string sourcePath, byte[] master)
    {
        string id = Guid.NewGuid().ToString();
        string target = Path.Combine(dataDir, id + ".bin");
        try
        {
            long size;
            using (var input = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                size = VaultCrypto.EncryptStream(input, output, master, "file:" + id);

            return new VaultItem
            {
                Id = id,
                Name = Path.GetFileName(sourcePath),
                Mime = GetMime(Path.GetExtension(sourcePath)),
                Size = size,
                Created = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };
        }
        catch
        {
            TryDelete(target);
            throw;
        }
    }

    internal void ExportFile(VaultItem item, string targetPath, byte[] master)
    {
        string source = Path.Combine(dataDir, item.Id + ".bin");
        if (!File.Exists(source)) throw new FileNotFoundException("הקובץ אינו נמצא בכספת");
        using var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read);
        using var output = new FileStream(targetPath, FileMode.Create, FileAccess.Write, FileShare.None);
        VaultCrypto.DecryptStream(input, output, master, "file:" + item.Id);
    }

    internal string DecryptToTemp(VaultItem item, byte[] master)
    {
        string tempDir = Path.Combine(baseDir, ".tmp");
        Directory.CreateDirectory(tempDir);
        string target = Path.Combine(tempDir, "preview-" + Guid.NewGuid().ToString("N"));
        string source = Path.Combine(dataDir, item.Id + ".bin");
        using var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.Read);
        using var output = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        try
        {
            VaultCrypto.DecryptStream(input, output, master, "file:" + item.Id);
            return target;
        }
        catch { TryDelete(target); throw; }
    }

    internal void Delete(VaultItem item) => TryDelete(Path.Combine(dataDir, item.Id + ".bin"));

    internal void WriteBackup(string targetPath, byte[] master)
    {
        CredentialSet credentials = LoadCredentials() ?? throw new InvalidOperationException("פרטי הכניסה חסרים");
        byte[] header = Credentials.ToJson(credentials);
        string tempZip = Path.Combine(Path.GetTempPath(), "vault-backup-" + Guid.NewGuid().ToString("N") + ".zip");
        try
        {
            using (var zipStream = new FileStream(tempZip, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None))
            using (var archive = new ZipArchive(zipStream, ZipArchiveMode.Create))
            {
                if (File.Exists(indexFile))
                {
                    var entry = archive.CreateEntry("index.enc", CompressionLevel.Optimal);
                    using var outStream = entry.Open();
                    using var inStream = File.OpenRead(indexFile);
                    inStream.CopyTo(outStream, 64 * 1024);
                }
                foreach (string file in Directory.GetFiles(dataDir, "*.bin"))
                {
                    var entry = archive.CreateEntry("data/" + Path.GetFileName(file), CompressionLevel.Optimal);
                    using var outStream = entry.Open();
                    using var inStream = File.OpenRead(file);
                    inStream.CopyTo(outStream, 64 * 1024);
                }
            }

            using var source = new FileStream(tempZip, FileMode.Open, FileAccess.Read, FileShare.Read);
            using var target = new FileStream(targetPath, FileMode.Create, FileAccess.Write, FileShare.None);
            target.Write(Encoding.ASCII.GetBytes("VLTBACK1"));
            VaultCrypto.WriteInt32(target, header.Length);
            target.Write(header);
            VaultCrypto.EncryptStream(source, target, master, "vault-backup-payload");
        }
        finally { TryDelete(tempZip); }
    }

    internal byte[] RestoreBackup(string sourcePath, string code)
    {
        string tempRoot = Path.Combine(Path.GetTempPath(), "vault-restore-" + Guid.NewGuid().ToString("N"));
        string tempZip = Path.Combine(tempRoot, "payload.zip");
        string tempData = Path.Combine(tempRoot, "data");
        Directory.CreateDirectory(tempRoot);
        byte[]? restoredMaster = null;
        try
        {
            CredentialSet credentials;
            using (var input = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                byte[] magic = VaultCrypto.ReadExactly(input, 8);
                if (Encoding.ASCII.GetString(magic) != "VLTBACK1") throw new InvalidDataException("קובץ הגיבוי אינו מזוהה");
                int headerLength = VaultCrypto.ReadInt32(input);
                if (headerLength < 1 || headerLength > 16_384) throw new InvalidDataException("כותרת הגיבוי פגומה");
                credentials = Credentials.FromJson(VaultCrypto.ReadExactly(input, headerLength));
                restoredMaster = Credentials.UnlockAny(credentials, code)
                    ?? throw new CryptographicException("הקוד אינו מתאים לגיבוי");
                using var output = new FileStream(tempZip, FileMode.CreateNew, FileAccess.Write, FileShare.None);
                VaultCrypto.DecryptStream(input, output, restoredMaster, "vault-backup-payload");
            }

            Directory.CreateDirectory(tempData);
            string? restoredIndex = null;
            using (var file = new FileStream(tempZip, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (var archive = new ZipArchive(file, ZipArchiveMode.Read))
            {
                long total = 0;
                int entries = 0;
                foreach (var entry in archive.Entries)
                {
                    entries++;
                    if (entries > 10_002) throw new InvalidDataException("בגיבוי יש יותר מדי פריטים");
                    string name = entry.FullName.Replace('\\', '/');
                    if (name == "index.enc")
                    {
                        if (entry.Length > 32 * 1024 * 1024) throw new InvalidDataException("רשימת הקבצים גדולה מדי");
                        restoredIndex = Path.Combine(tempRoot, "index.enc");
                        using var output = File.Create(restoredIndex);
                        using var input = entry.Open();
                        input.CopyTo(output);
                    }
                    else if (name.StartsWith("data/", StringComparison.Ordinal) &&
                             name.EndsWith(".bin", StringComparison.OrdinalIgnoreCase) &&
                             Guid.TryParse(Path.GetFileNameWithoutExtension(name), out _))
                    {
                        if (entry.Length < 0 || entry.Length > 8L * 1024 * 1024 * 1024)
                            throw new InvalidDataException("קובץ בגיבוי גדול מדי");
                        total += entry.Length;
                        if (total > 8L * 1024 * 1024 * 1024) throw new InvalidDataException("הגיבוי גדול מדי");
                        string target = Path.Combine(tempData, Path.GetFileName(name));
                        using var output = File.Create(target);
                        using var input = entry.Open();
                        input.CopyTo(output, 64 * 1024);
                    }
                    else throw new InvalidDataException("הגיבוי מכיל נתיב לא מורשה");
                }
            }

            if (restoredIndex == null) throw new InvalidDataException("רשימת הקבצים חסרה בגיבוי");
            byte[] encryptedIndex = File.ReadAllBytes(restoredIndex);
            byte[] clearIndex = VaultCrypto.OpenBytes(encryptedIndex, restoredMaster, Encoding.UTF8.GetBytes("vault-index"));
            try { _ = JsonSerializer.Deserialize<List<VaultItem>>(clearIndex) ?? throw new InvalidDataException("רשימת הקבצים בגיבוי פגומה"); }
            finally { CryptographicOperations.ZeroMemory(clearIndex); }

            string oldData = dataDir + ".old";
            TryDeleteDirectory(oldData);
            if (Directory.Exists(dataDir)) Directory.Move(dataDir, oldData);
            Directory.Move(tempData, dataDir);
            TryDeleteDirectory(oldData);
            WriteAtomic(indexFile, encryptedIndex);
            SaveCredentials(credentials);
            restoredMaster = restoredMaster.ToArray();
            return restoredMaster;
        }
        catch
        {
            if (restoredMaster != null) CryptographicOperations.ZeroMemory(restoredMaster);
            throw;
        }
        finally { TryDeleteDirectory(tempRoot); }
    }

    internal SettingsDocument LoadSettings()
    {
        try
        {
            if (!File.Exists(settingsFile)) return new SettingsDocument();
            return JsonSerializer.Deserialize<SettingsDocument>(File.ReadAllBytes(settingsFile)) ?? new SettingsDocument();
        }
        catch { return new SettingsDocument(); }
    }

    internal void SaveSettings(SettingsDocument settings)
    {
        settings.ScareThreshold = Math.Clamp(settings.ScareThreshold, 1, 5);
        settings.LockoutLevel = Math.Clamp(settings.LockoutLevel, 0, 6);
        if (settings.AttemptHistory.Count > 100)
            settings.AttemptHistory = settings.AttemptHistory.TakeLast(100).ToList();
        WriteAtomic(settingsFile, JsonSerializer.SerializeToUtf8Bytes(settings));
    }

    internal FailureRecord RecordFailure()
    {
        SettingsDocument settings = LoadSettings();
        settings.ConsecutiveFailures++;
        settings.TotalFailures++;
        var attempt = new FailedAttempt
        {
            Id = Guid.NewGuid().ToString(),
            Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Count = settings.ConsecutiveFailures
        };
        settings.AttemptHistory.Add(attempt);
        if (settings.AttemptHistory.Count > 100)
            settings.AttemptHistory.RemoveRange(0, settings.AttemptHistory.Count - 100);
        SaveSettings(settings);
        return new FailureRecord(attempt.Id, settings.ConsecutiveFailures, settings.TotalFailures);
    }

    internal void ResetConsecutiveFailures()
    {
        SettingsDocument settings = LoadSettings();
        settings.ConsecutiveFailures = 0;
        SaveSettings(settings);
    }

    internal void ClearAttemptHistory()
    {
        SettingsDocument settings = LoadSettings();
        settings.AttemptHistory.Clear();
        settings.TotalFailures = 0;
        settings.ConsecutiveFailures = 0;
        SaveSettings(settings);
    }

    private static void WriteAtomic(string path, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        string temp = path + ".tmp";
        File.WriteAllBytes(temp, bytes);
        if (File.Exists(path)) File.Replace(temp, path, null, true);
        else File.Move(temp, path);
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static void TryDeleteDirectory(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, true); } catch { }
    }

    private static string GetMime(string extension)
    {
        return extension.ToLowerInvariant() switch
        {
            ".png" => "image/png",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".gif" => "image/gif",
            ".bmp" => "image/bmp",
            ".webp" => "image/webp",
            ".pdf" => "application/pdf",
            ".txt" => "text/plain",
            ".json" => "application/json",
            ".xml" => "application/xml",
            ".html" or ".htm" => "text/html",
            ".csv" => "text/csv",
            ".mp4" => "video/mp4",
            ".mp3" => "audio/mpeg",
            ".zip" => "application/zip",
            _ => "application/octet-stream"
        };
    }
}