#pragma once
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

namespace vault {
struct VaultItem { std::string id, name, mime; std::uint64_t size{}; std::int64_t created{}; };
struct FailedAttempt { std::string id; std::int64_t time{}; int count{}; };
struct SettingsDocument {
    bool scareEnabled{true}; int scareThreshold{2}; int consecutiveFailures{};
    int totalFailures{}; std::int64_t lockoutUntil{}; int lockoutLevel{};
    std::vector<FailedAttempt> attemptHistory;
};
struct KeyEnvelope { std::vector<std::uint8_t> salt, wrapped; };
struct CredentialSet { KeyEnvelope pin, pattern; };

std::vector<std::uint8_t> RandomBytes(std::size_t size);
std::vector<std::uint8_t> DeriveKey(const std::string& code, const std::vector<std::uint8_t>& salt);
std::vector<std::uint8_t> SealBytes(const std::vector<std::uint8_t>& plain, const std::vector<std::uint8_t>& key, const std::vector<std::uint8_t>& aad);
std::vector<std::uint8_t> OpenBytes(const std::vector<std::uint8_t>& blob, const std::vector<std::uint8_t>& key, const std::vector<std::uint8_t>& aad);
void EncryptStream(const std::filesystem::path& input, const std::filesystem::path& output,
                   const std::vector<std::uint8_t>& key, const std::string& context, std::uint64_t* sizeOut = nullptr);
void DecryptStream(const std::filesystem::path& input, const std::filesystem::path& output,
                   const std::vector<std::uint8_t>& key, const std::string& context);
CredentialSet CreateCredentials(const std::vector<std::uint8_t>& master, const std::string& pin, const std::string& pattern);
std::vector<std::uint8_t> UnlockCredentials(const CredentialSet& credentials, const std::string& code, const std::string& type);
CredentialSet ReplaceCredential(const CredentialSet& credentials, const std::vector<std::uint8_t>& master,
                                const std::string& code, const std::string& type);
std::int64_t UnixMillisNow();
std::string NewId();
std::string MimeForExtension(const std::wstring& extension);
std::string FormatBytes(std::uint64_t bytes);

class VaultStore {
public:
    VaultStore();
    const std::filesystem::path& BaseDir() const { return baseDir; }
    bool HasCredentials() const;
    CredentialSet LoadCredentials() const;
    void SaveCredentials(const CredentialSet& credentials) const;
    std::vector<VaultItem> LoadIndex(const std::vector<std::uint8_t>& master) const;
    void SaveIndex(const std::vector<VaultItem>& items, const std::vector<std::uint8_t>& master) const;
    VaultItem ImportFile(const std::filesystem::path& source, const std::vector<std::uint8_t>& master);
    void ExportFile(const VaultItem& item, const std::filesystem::path& destination, const std::vector<std::uint8_t>& master) const;
    std::filesystem::path DecryptToTemp(const VaultItem& item, const std::vector<std::uint8_t>& master) const;
    void Delete(const VaultItem& item) const;
    SettingsDocument LoadSettings() const;
    void SaveSettings(SettingsDocument settings) const;
    FailedAttempt RecordFailure();
    void ResetConsecutiveFailures() const;
    void ClearAttemptHistory() const;
    void WriteBackup(const std::filesystem::path& destination, const std::vector<std::uint8_t>& master) const;
    std::vector<std::uint8_t> RestoreBackup(const std::filesystem::path& source, const std::string& code);
private:
    std::filesystem::path baseDir, dataDir, indexFile, credentialsFile, settingsFile;
};
}
