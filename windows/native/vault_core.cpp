#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <shlobj.h>
#include <wincrypt.h>
#include <objbase.h>
#include "vault_core.hpp"
#include "json.hpp"
#include "miniz.h"
#include <algorithm>
#include <array>
#include <cwctype>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <stdexcept>

#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shell32.lib")

namespace vault {
namespace fs = std::filesystem;
using json = nlohmann::json;
static constexpr std::size_t CHUNK = 64 * 1024, NONCE = 12, TAG = 16;
static constexpr std::uint32_t MAGIC = 0x564C5431, ITERATIONS = 150000;

static void ck(NTSTATUS s, const char* op) {
    if (!BCRYPT_SUCCESS(s)) {
        std::ostringstream m; m << op << " נכשל (0x" << std::hex << static_cast<unsigned long>(s) << ")";
        throw std::runtime_error(m.str());
    }
}
struct Alg { BCRYPT_ALG_HANDLE h{}; ~Alg(){ if(h) BCryptCloseAlgorithmProvider(h,0); } };
struct Key { BCRYPT_KEY_HANDLE h{}; ~Key(){ if(h) BCryptDestroyKey(h); } };

static std::string path8(const fs::path& p) {
    auto u=p.u8string(); return std::string(reinterpret_cast<const char*>(u.data()),u.size());
}
static std::wstring toWide(const std::string& s) {
    if(s.empty()) return {};
    int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),(int)s.size(),nullptr,0);
    if(n<=0) throw std::runtime_error("טקסט UTF-8 לא תקין");
    std::wstring o(n,L'\0'); MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),(int)s.size(),o.data(),n); return o;
}
static std::string toUtf8(const std::wstring& s) {
    if(s.empty()) return {};
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),(int)s.size(),nullptr,0,nullptr,nullptr);
    if(n<=0) throw std::runtime_error("המרת טקסט נכשלה");
    std::string o(n,'\0'); WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),(int)s.size(),o.data(),n,nullptr,nullptr); return o;
}
static std::vector<std::uint8_t> readAll(const fs::path& p, std::uint64_t max=1024ULL*1024*1024) {
    std::ifstream f(p,std::ios::binary|std::ios::ate); if(!f) throw std::runtime_error("לא ניתן לפתוח קובץ");
    auto n=f.tellg(); if(n<0 || (std::uint64_t)n>max) throw std::runtime_error("הקובץ גדול מדי או פגום");
    std::vector<std::uint8_t> b((size_t)n); f.seekg(0); if(!b.empty()) f.read((char*)b.data(),(std::streamsize)b.size());
    if(!f && !b.empty()) throw std::runtime_error("קריאת הקובץ נכשלה"); return b;
}
static void atomicWrite(const fs::path& p,const std::vector<std::uint8_t>& b) {
    fs::create_directories(p.parent_path()); fs::path t=p; t+=L".tmp";
    { std::ofstream f(t,std::ios::binary|std::ios::trunc); if(!f) throw std::runtime_error("לא ניתן ליצור קובץ זמני");
      if(!b.empty()) f.write((const char*)b.data(),(std::streamsize)b.size()); f.flush(); if(!f) throw std::runtime_error("כתיבה נכשלה"); }
    if(!MoveFileExW(t.c_str(),p.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)) {
        DeleteFileW(t.c_str()); throw std::runtime_error("לא ניתן לשמור את הקובץ");
    }
}
static std::string b64(const std::vector<std::uint8_t>& b) {
    if(b.empty()) return {}; DWORD n=0;
    if(!CryptBinaryToStringA(b.data(),(DWORD)b.size(),CRYPT_STRING_BASE64|CRYPT_STRING_NOCRLF,nullptr,&n))
        throw std::runtime_error("קידוד נתוני כניסה נכשל");
    std::string s(n,'\0');
    if(!CryptBinaryToStringA(b.data(),(DWORD)b.size(),CRYPT_STRING_BASE64|CRYPT_STRING_NOCRLF,s.data(),&n))
        throw std::runtime_error("קידוד נתוני כניסה נכשל");
    while(!s.empty()&&s.back()==0)s.pop_back(); return s;
}
static std::vector<std::uint8_t> unb64(const std::string& s) {
    DWORD n=0; if(!CryptStringToBinaryA(s.c_str(),(DWORD)s.size(),CRYPT_STRING_BASE64,nullptr,&n,nullptr,nullptr))
        throw std::runtime_error("פרטי הכניסה פגומים");
    std::vector<std::uint8_t> b(n);
    if(!CryptStringToBinaryA(s.c_str(),(DWORD)s.size(),CRYPT_STRING_BASE64,b.data(),&n,nullptr,nullptr))
        throw std::runtime_error("פרטי הכניסה פגומים");
    b.resize(n); return b;
}
static std::vector<std::uint8_t> aadFor(const std::string& context,std::uint64_t i) {
    std::vector<std::uint8_t> a(context.begin(),context.end());
    for(int shift=56;shift>=0;shift-=8)a.push_back((std::uint8_t)((i>>shift)&255)); return a;
}
static std::vector<std::uint8_t> aesCrypt(bool encrypt,const std::vector<std::uint8_t>& input,
    const std::vector<std::uint8_t>& keyBytes,const std::vector<std::uint8_t>& nonce,
    const std::vector<std::uint8_t>& aad,std::vector<std::uint8_t>& tag) {
    if(keyBytes.size()!=32||nonce.size()!=NONCE) throw std::runtime_error("מפתח או nonce לא תקינים");
    Alg alg; ck(BCryptOpenAlgorithmProvider(&alg.h,BCRYPT_AES_ALGORITHM,nullptr,0),"פתיחת AES");
    const wchar_t mode[]=BCRYPT_CHAIN_MODE_GCM;
    ck(BCryptSetProperty(alg.h,BCRYPT_CHAINING_MODE,(PUCHAR)mode,sizeof(mode),0),"הגדרת AES-GCM");
    DWORD objLen=0,ret=0;
    ck(BCryptGetProperty(alg.h,BCRYPT_OBJECT_LENGTH,(PUCHAR)&objLen,sizeof(objLen),&ret,0),"קריאת גודל מפתח");
    std::vector<std::uint8_t> obj(objLen); Key k;
    ck(BCryptGenerateSymmetricKey(alg.h,&k.h,obj.data(),objLen,(PUCHAR)keyBytes.data(),(ULONG)keyBytes.size(),0),"יצירת מפתח AES");
    std::vector<std::uint8_t> out(input.size());
    if(encrypt) tag.assign(TAG,0);
    else if(tag.size()!=TAG) throw std::runtime_error("תג AES-GCM לא תקין");
    BCRYPT_AUTHENTICATED_CIPHER_MODE_INFO info; BCRYPT_INIT_AUTH_MODE_INFO(info);
    info.pbNonce=(PUCHAR)nonce.data(); info.cbNonce=(ULONG)nonce.size();
    info.pbAuthData=aad.empty()?nullptr:(PUCHAR)aad.data(); info.cbAuthData=(ULONG)aad.size();
    info.pbTag=tag.data(); info.cbTag=(ULONG)tag.size();
    ULONG written=0;
    NTSTATUS status= encrypt
      ? BCryptEncrypt(k.h,input.empty()?nullptr:(PUCHAR)input.data(),(ULONG)input.size(),&info,nullptr,0,
                      out.empty()?nullptr:out.data(),(ULONG)out.size(),&written,0)
      : BCryptDecrypt(k.h,input.empty()?nullptr:(PUCHAR)input.data(),(ULONG)input.size(),&info,nullptr,0,
                      out.empty()?nullptr:out.data(),(ULONG)out.size(),&written,0);
    SecureZeroMemory(obj.data(),obj.size()); ck(status,encrypt?"הצפנת AES-GCM":"פענוח AES-GCM");
    out.resize(written); return out;
}
static void writeBe(std::ostream& f,std::uint32_t v) {
    char b[4]={(char)(v>>24),(char)(v>>16),(char)(v>>8),(char)v}; f.write(b,4);
    if(!f) throw std::runtime_error("כתיבת קובץ נכשלה");
}
static std::uint32_t readBe(std::istream& f) {
    unsigned char b[4]{}; f.read((char*)b,4); if(f.gcount()!=4) throw std::runtime_error("קובץ מוצפן נקטע");
    return ((std::uint32_t)b[0]<<24)|((std::uint32_t)b[1]<<16)|((std::uint32_t)b[2]<<8)|b[3];
}
static std::vector<std::uint8_t> readExact(std::istream& f,size_t n) {
    std::vector<std::uint8_t> b(n); if(n) f.read((char*)b.data(),(std::streamsize)n);
    if(n && (size_t)f.gcount()!=n) throw std::runtime_error("קובץ מוצפן נקטע"); return b;
}
static json itemJson(const VaultItem& i) {
    return json{{"id",i.id},{"name",i.name},{"mime",i.mime},{"size",i.size},{"created",i.created}};
}
static VaultItem itemFrom(const json& j) {
    VaultItem i{j.at("id").get<std::string>(),j.at("name").get<std::string>(),
        j.at("mime").get<std::string>(),j.at("size").get<std::uint64_t>(),j.at("created").get<std::int64_t>()};
    if(i.id.empty()||i.name.empty()) throw std::runtime_error("רשומת קובץ פגומה"); return i;
}
static std::vector<std::uint8_t> credentialJson(const CredentialSet& c) {
    json j{{"version",1},{"pinSalt",b64(c.pin.salt)},{"pinWrapped",b64(c.pin.wrapped)},
           {"patternSalt",b64(c.pattern.salt)},{"patternWrapped",b64(c.pattern.wrapped)}};
    std::string s=j.dump(); return {s.begin(),s.end()};
}
static CredentialSet credentialsFrom(const std::vector<std::uint8_t>& b) {
    json j=json::parse(b.begin(),b.end()); if(j.at("version").get<int>()!=1) throw std::runtime_error("גרסת פרטי הכניסה אינה נתמכת");
    CredentialSet c{{unb64(j.at("pinSalt").get<std::string>()),unb64(j.at("pinWrapped").get<std::string>())},
                    {unb64(j.at("patternSalt").get<std::string>()),unb64(j.at("patternWrapped").get<std::string>())}};
    if(c.pin.salt.size()!=16||c.pattern.salt.size()!=16||c.pin.wrapped.size()!=60||c.pattern.wrapped.size()!=60)
        throw std::runtime_error("מבנה פרטי הכניסה אינו תקין");
    return c;
}
static json settingsJson(const SettingsDocument& s) {
    json h=json::array(); for(auto& e:s.attemptHistory) h.push_back({{"id",e.id},{"time",e.time},{"count",e.count}});
    return {{"scareEnabled",s.scareEnabled},{"scareThreshold",std::clamp(s.scareThreshold,1,5)},
      {"consecutiveFailures",s.consecutiveFailures},{"totalFailures",s.totalFailures},
      {"lockoutUntil",s.lockoutUntil},{"lockoutLevel",std::clamp(s.lockoutLevel,0,6)},{"attemptHistory",h}};
}
static SettingsDocument settingsFrom(const json& j) {
    SettingsDocument s; s.scareEnabled=j.value("scareEnabled",true);
    s.scareThreshold=std::clamp(j.value("scareThreshold",2),1,5);
    s.consecutiveFailures=std::max(0,j.value("consecutiveFailures",0)); s.totalFailures=std::max(0,j.value("totalFailures",0));
    s.lockoutUntil=j.value("lockoutUntil",std::int64_t{0}); s.lockoutLevel=std::clamp(j.value("lockoutLevel",0),0,6);
    if(j.contains("attemptHistory")&&j["attemptHistory"].is_array()) for(auto& e:j["attemptHistory"]) {
        try { s.attemptHistory.push_back({e.at("id").get<std::string>(),e.at("time").get<std::int64_t>(),e.at("count").get<int>()}); } catch(...) {}
    }
    if(s.attemptHistory.size()>100) s.attemptHistory.erase(s.attemptHistory.begin(),s.attemptHistory.end()-100);
    return s;
}
static bool isGuid(const std::string& s) {
    if(s.size()!=36) return false;
    for(size_t i=0;i<s.size();++i) {
        if(i==8||i==13||i==18||i==23) {if(s[i]!='-') return false;}
        else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')||(s[i]>='A'&&s[i]<='F'))) return false;
    } return true;
}
}

std::vector<std::uint8_t> RandomBytes(std::size_t n) {
    std::vector<std::uint8_t> b(n); if(n) ck(BCryptGenRandom(nullptr,b.data(),(ULONG)n,BCRYPT_USE_SYSTEM_PREFERRED_RNG),"יצירת נתון אקראי"); return b;
}
std::vector<std::uint8_t> DeriveKey(const std::string& code,const std::vector<std::uint8_t>& salt) {
    Alg a; ck(BCryptOpenAlgorithmProvider(&a.h,BCRYPT_SHA1_ALGORITHM,nullptr,BCRYPT_ALG_HANDLE_HMAC_FLAG),"פתיחת SHA-1");
    std::vector<std::uint8_t> key(32);
    ck(BCryptDeriveKeyPBKDF2(a.h,(PUCHAR)code.data(),(ULONG)code.size(),(PUCHAR)salt.data(),(ULONG)salt.size(),
                            ITERATIONS,key.data(),(ULONG)key.size(),0),"גזירת מפתח"); return key;
}
std::vector<std::uint8_t> SealBytes(const std::vector<std::uint8_t>& plain,const std::vector<std::uint8_t>& key,const std::vector<std::uint8_t>& aad) {
    auto nonce=RandomBytes(NONCE); std::vector<std::uint8_t> tag; auto cipher=aesCrypt(true,plain,key,nonce,aad,tag);
    std::vector<std::uint8_t> out; out.reserve(NONCE+cipher.size()+TAG);
    out.insert(out.end(),nonce.begin(),nonce.end()); out.insert(out.end(),cipher.begin(),cipher.end()); out.insert(out.end(),tag.begin(),tag.end());
    if(!cipher.empty()) SecureZeroMemory(cipher.data(),cipher.size()); return out;
}
std::vector<std::uint8_t> OpenBytes(const std::vector<std::uint8_t>& blob,const std::vector<std::uint8_t>& key,const std::vector<std::uint8_t>& aad) {
    if(blob.size()<28) throw std::runtime_error("נתוני ההצפנה פגומים");
    std::vector<std::uint8_t> nonce(blob.begin(),blob.begin()+12), cipher(blob.begin()+12,blob.end()-16), tag(blob.end()-16,blob.end());
    auto plain=aesCrypt(false,cipher,key,nonce,aad,tag); if(!cipher.empty()) SecureZeroMemory(cipher.data(),cipher.size()); return plain;
}
void EncryptStream(const fs::path& src,const fs::path& dst,const std::vector<std::uint8_t>& key,const std::string& ctx,std::uint64_t* sizeOut) {
    std::ifstream in(src,std::ios::binary); std::ofstream out(dst,std::ios::binary|std::ios::trunc);
    if(!in||!out) throw std::runtime_error("לא ניתן לפתוח קובץ להצפנה");
    writeBe(out,MAGIC); std::vector<std::uint8_t> buf(CHUNK); std::uint64_t idx=0,total=0;
    while(in) {
        in.read((char*)buf.data(),(std::streamsize)buf.size()); auto count=in.gcount(); if(count<=0) break;
        std::vector<std::uint8_t> plain(buf.begin(),buf.begin()+count), tag;
        auto nonce=RandomBytes(NONCE), aad=aadFor(ctx,idx); auto cipher=aesCrypt(true,plain,key,nonce,aad,tag);
        writeBe(out,(std::uint32_t)count); out.write((char*)nonce.data(),(std::streamsize)nonce.size());
        out.write((char*)cipher.data(),(std::streamsize)cipher.size()); out.write((char*)tag.data(),(std::streamsize)tag.size());
        if(!out) throw std::runtime_error("כתיבת קובץ מוצפן נכשלה");
        total+=(std::uint64_t)count; ++idx; SecureZeroMemory(plain.data(),plain.size()); SecureZeroMemory(cipher.data(),cipher.size());
    }
    writeBe(out,0); out.flush(); if(!out) throw std::runtime_error("שמירת קובץ מוצפן נכשלה"); if(sizeOut)*sizeOut=total;
}
void DecryptStream(const fs::path& src,const fs::path& dst,const std::vector<std::uint8_t>& key,const std::string& ctx) {
    std::ifstream in(src,std::ios::binary); std::ofstream out(dst,std::ios::binary|std::ios::trunc);
    if(!in||!out) throw std::runtime_error("לא ניתן לפתוח קובץ לפענוח"); if(readBe(in)!=MAGIC) throw std::runtime_error("קובץ מוצפן לא מזוהה");
    std::uint64_t idx=0,total=0;
    while(true) {
        auto n=readBe(in); if(!n) break; if(n>1024U*1024U) throw std::runtime_error("מבנה קובץ לא תקין");
        auto nonce=readExact(in,NONCE), cipher=readExact(in,n), tag=readExact(in,TAG), aad=aadFor(ctx,idx);
        auto plain=aesCrypt(false,cipher,key,nonce,aad,tag);
        if(plain.size()!=n) throw std::runtime_error("אורך מקטע לא תקין");
        out.write((char*)plain.data(),(std::streamsize)plain.size()); if(!out) throw std::runtime_error("כתיבת קובץ מפוענח נכשלה");
        SecureZeroMemory(plain.data(),plain.size()); SecureZeroMemory(cipher.data(),cipher.size()); ++idx; total+=n;
        if(total>8ULL*1024ULL*1024ULL*1024ULL) throw std::runtime_error("הקובץ גדול מהמותר");
    } out.flush(); if(!out) throw std::runtime_error("שמירת קובץ מפוענח נכשלה");
}
CredentialSet CreateCredentials(const std::vector<std::uint8_t>& master,const std::string& pin,const std::string& pattern) {
    auto wrap=[&](const std::string& code){ KeyEnvelope e; e.salt=RandomBytes(16); auto k=DeriveKey(code,e.salt);
        std::string t="vault-master-key"; std::vector<std::uint8_t> aad(t.begin(),t.end()); e.wrapped=SealBytes(master,k,aad);
        SecureZeroMemory(k.data(),k.size()); return e; };
    return {wrap(pin),wrap(pattern)};
}
std::vector<std::uint8_t> UnlockCredentials(const CredentialSet& c,const std::string& code,const std::string& type) {
    const auto& e=type=="pin"?c.pin:c.pattern; auto k=DeriveKey(code,e.salt); std::string t="vault-master-key";
    std::vector<std::uint8_t> aad(t.begin(),t.end()); try { auto m=OpenBytes(e.wrapped,k,aad); SecureZeroMemory(k.data(),k.size());
        if(m.size()!=32){if(!m.empty())SecureZeroMemory(m.data(),m.size());return {};} return m;
    } catch(...) {SecureZeroMemory(k.data(),k.size());return {};}
}
CredentialSet ReplaceCredential(const CredentialSet& c,const std::vector<std::uint8_t>& master,const std::string& code,const std::string& type) {
    CredentialSet out=c; KeyEnvelope e; e.salt=RandomBytes(16); auto k=DeriveKey(code,e.salt); std::string t="vault-master-key";
    std::vector<std::uint8_t> aad(t.begin(),t.end()); e.wrapped=SealBytes(master,k,aad); SecureZeroMemory(k.data(),k.size());
    if(type=="pin")out.pin=std::move(e);else out.pattern=std::move(e); return out;
}
std::int64_t UnixMillisNow() {
    FILETIME ft{}; GetSystemTimeAsFileTime(&ft); ULARGE_INTEGER u{}; u.LowPart=ft.dwLowDateTime;u.HighPart=ft.dwHighDateTime;
    return (std::int64_t)((u.QuadPart-116444736000000000ULL)/10000ULL);
}
std::string NewId() {
    auto b=RandomBytes(16); b[6]=(b[6]&15)|64; b[8]=(b[8]&63)|128; static const char* h="0123456789abcdef"; std::string s;
    for(size_t i=0;i<b.size();++i){if(i==4||i==6||i==8||i==10)s.push_back('-');s.push_back(h[b[i]>>4]);s.push_back(h[b[i]&15]);} return s;
}
std::string MimeForExtension(const std::wstring& ext) {
    std::wstring e=ext; std::transform(e.begin(),e.end(),e.begin(),towlower);
    if(e==L".png")return "image/png"; if(e==L".jpg"||e==L".jpeg")return "image/jpeg"; if(e==L".gif")return "image/gif";
    if(e==L".bmp")return "image/bmp";if(e==L".webp")return "image/webp";if(e==L".pdf")return "application/pdf";
    if(e==L".txt")return "text/plain";if(e==L".json")return "application/json";if(e==L".xml")return "application/xml";
    if(e==L".html"||e==L".htm")return "text/html";if(e==L".csv")return "text/csv";if(e==L".mp4")return "video/mp4";
    if(e==L".mp3")return "audio/mpeg";if(e==L".zip")return "application/zip";return "application/octet-stream";
}
std::string FormatBytes(std::uint64_t b) {
    std::ostringstream o;o<<std::fixed<<std::setprecision(1);
    if(b<1024)o<<b<<" בייט";else if(b<1024ULL*1024)o<<b/1024.0<<" KB";
    else if(b<1024ULL*1024*1024)o<<b/(1024.0*1024)<<" MB";
    else{o<<std::setprecision(2)<<b/(1024.0*1024*1024)<<" GB";}return o.str();
}

VaultStore::VaultStore() {
    PWSTR local=nullptr; if(FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData,KF_FLAG_CREATE,nullptr,&local))||!local)
        throw std::runtime_error("לא ניתן לגשת לתיקיית הנתונים המקומית");
    baseDir=fs::path(local)/L"כספת"/L"vault";CoTaskMemFree(local);
    dataDir=baseDir/L"data";indexFile=baseDir/L"index.enc";credentialsFile=baseDir/L"credentials.json";settingsFile=baseDir/L"settings.json";
    fs::create_directories(dataDir);
}
bool VaultStore::HasCredentials() const {try{(void)LoadCredentials();return fs::exists(credentialsFile);}catch(...){return false;}}
CredentialSet VaultStore::LoadCredentials() const {return credentialsFrom(readAll(credentialsFile,64*1024));}
void VaultStore::SaveCredentials(const CredentialSet& c) const {atomicWrite(credentialsFile,credentialJson(c));}
std::vector<VaultItem> VaultStore::LoadIndex(const std::vector<std::uint8_t>& master) const {
    if(!fs::exists(indexFile))return {};auto enc=readAll(indexFile,32*1024*1024);std::string t="vault-index";
    std::vector<std::uint8_t> aad(t.begin(),t.end());auto plain=OpenBytes(enc,master,aad);
    try{auto j=json::parse(plain.begin(),plain.end());if(!j.is_array())throw std::runtime_error("רשימת הקבצים פגומה");
        std::vector<VaultItem> items;for(auto& v:j)items.push_back(itemFrom(v));SecureZeroMemory(plain.data(),plain.size());return items;
    }catch(...){if(!plain.empty())SecureZeroMemory(plain.data(),plain.size());throw;}
}
void VaultStore::SaveIndex(const std::vector<VaultItem>& items,const std::vector<std::uint8_t>& master) const {
    json j=json::array();for(auto& i:items)j.push_back(itemJson(i));std::string s=j.dump();std::vector<std::uint8_t> plain(s.begin(),s.end());
    std::string t="vault-index";std::vector<std::uint8_t> aad(t.begin(),t.end());auto enc=SealBytes(plain,master,aad);
    atomicWrite(indexFile,enc);SecureZeroMemory(plain.data(),plain.size());SecureZeroMemory(enc.data(),enc.size());
}
VaultItem VaultStore::ImportFile(const fs::path& src,const std::vector<std::uint8_t>& master) {
    if(!fs::exists(src)||!fs::is_regular_file(src))throw std::runtime_error("הקובץ שנבחר אינו קובץ רגיל");
    std::string id=NewId();fs::path dest=dataDir/(toWide(id)+L".bin");std::uint64_t size=0;
    try{EncryptStream(src,dest,master,"file:"+id,&size);return {id,toUtf8(src.filename().wstring()),MimeForExtension(src.extension().wstring()),size,UnixMillisNow()};}
    catch(...){std::error_code ec;fs::remove(dest,ec);throw;}
}
void VaultStore::ExportFile(const VaultItem& item,const fs::path& dst,const std::vector<std::uint8_t>& master) const {
    fs::path src=dataDir/(toWide(item.id)+L".bin");if(!fs::exists(src))throw std::runtime_error("הקובץ אינו נמצא בכספת");
    DecryptStream(src,dst,master,"file:"+item.id);
}
fs::path VaultStore::DecryptToTemp(const VaultItem& item,const std::vector<std::uint8_t>& master) const {
    fs::path dir=baseDir/L".tmp";fs::create_directories(dir);fs::path target=dir/(L"preview-"+toWide(NewId()));
    try{DecryptStream(dataDir/(toWide(item.id)+L".bin"),target,master,"file:"+item.id);return target;}
    catch(...){std::error_code ec;fs::remove(target,ec);throw;}
}
void VaultStore::Delete(const VaultItem& item) const {if(!fs::remove(dataDir/(toWide(item.id)+L".bin")))throw std::runtime_error("לא ניתן למחוק את הקובץ מהכספת");}
SettingsDocument VaultStore::LoadSettings() const {
    try{if(!fs::exists(settingsFile))return {};auto b=readAll(settingsFile,1024*1024);return settingsFrom(json::parse(b.begin(),b.end()));}catch(...){return {};}
}
void VaultStore::SaveSettings(SettingsDocument s) const {
    s.scareThreshold=std::clamp(s.scareThreshold,1,5);s.lockoutLevel=std::clamp(s.lockoutLevel,0,6);
    if(s.attemptHistory.size()>100)s.attemptHistory.erase(s.attemptHistory.begin(),s.attemptHistory.end()-100);
    std::string x=settingsJson(s).dump();atomicWrite(settingsFile,{x.begin(),x.end()});
}
FailedAttempt VaultStore::RecordFailure() {
    auto s=LoadSettings();++s.consecutiveFailures;++s.totalFailures;FailedAttempt a{NewId(),UnixMillisNow(),s.consecutiveFailures};
    s.attemptHistory.push_back(a);if(s.attemptHistory.size()>100)s.attemptHistory.erase(s.attemptHistory.begin(),s.attemptHistory.end()-100);
    SaveSettings(s);return a;
}
void VaultStore::ResetConsecutiveFailures() const {auto s=LoadSettings();s.consecutiveFailures=0;SaveSettings(s);}
void VaultStore::ClearAttemptHistory() const {auto s=LoadSettings();s.attemptHistory.clear();s.totalFailures=0;s.consecutiveFailures=0;SaveSettings(s);}

void VaultStore::WriteBackup(const fs::path& dst,const std::vector<std::uint8_t>& master) const {
    if(!fs::exists(indexFile))throw std::runtime_error("רשימת הקבצים אינה קיימת");auto header=credentialJson(LoadCredentials());
    fs::path z=fs::temp_directory_path()/(L"vault-backup-"+toWide(NewId())+L".zip");mz_zip_archive a{};
    if(!mz_zip_writer_init_file(&a,path8(z).c_str(),0))throw std::runtime_error("לא ניתן ליצור ZIP לגיבוי");
    bool ended=false;
    try{
        if(!mz_zip_writer_add_file(&a,"index.enc",path8(indexFile).c_str(),nullptr,0,MZ_DEFAULT_COMPRESSION))
            throw std::runtime_error("לא ניתן להוסיף את רשימת הקבצים");
        for(auto& e:fs::directory_iterator(dataDir)){
            if(e.is_regular_file()&&e.path().extension()==L".bin"){
                std::string name="data/"+path8(e.path().filename());
                if(!mz_zip_writer_add_file(&a,name.c_str(),path8(e.path()).c_str(),nullptr,0,MZ_DEFAULT_COMPRESSION))
                    throw std::runtime_error("לא ניתן להוסיף קובץ לגיבוי");
            }
        }
        if(!mz_zip_writer_finalize_archive(&a))throw std::runtime_error("לא ניתן לסיים ZIP");
        mz_zip_writer_end(&a);ended=true;
        fs::path encTemp=fs::temp_directory_path()/(L"vault-backup-"+toWide(NewId())+L".enc");
        try{
            EncryptStream(z,encTemp,master,"vault-backup-payload");
            std::ofstream out(dst,std::ios::binary|std::ios::trunc);
            if(!out)throw std::runtime_error("לא ניתן ליצור קובץ גיבוי");
            out.write("VLTBACK1",8);writeBe(out,(std::uint32_t)header.size());
            out.write((char*)header.data(),(std::streamsize)header.size());out.flush();out.close();
            std::ifstream enc(encTemp,std::ios::binary);std::ofstream append(dst,std::ios::binary|std::ios::app);
            if(!enc||!append)throw std::runtime_error("לא ניתן להשלים את קובץ הגיבוי");
            append<<enc.rdbuf();append.flush();if(!append)throw std::runtime_error("כתיבת הגיבוי נכשלה");
        }catch(...){std::error_code ec;fs::remove(encTemp,ec);throw;}
    }catch(...){if(!ended)mz_zip_writer_end(&a);std::error_code ec;fs::remove(z,ec);throw;}
    std::error_code ec;fs::remove(z,ec);
}
std::vector<std::uint8_t> VaultStore::RestoreBackup(const fs::path& src,const std::string& code) {
    fs::path root=fs::temp_directory_path()/(L"vault-restore-"+toWide(NewId()));fs::create_directories(root);
    std::vector<std::uint8_t> master;
    try{
        std::ifstream in(src,std::ios::binary);if(!in)throw std::runtime_error("לא ניתן לקרוא גיבוי");
        char magic[8]{};in.read(magic,8);if(in.gcount()!=8||std::string(magic,8)!="VLTBACK1")throw std::runtime_error("קובץ הגיבוי אינו מזוהה");
        auto n=readBe(in);if(n<1||n>16384)throw std::runtime_error("כותרת הגיבוי פגומה");
        std::vector<std::uint8_t> header(n);in.read((char*)header.data(),n);if((std::uint32_t)in.gcount()!=n)throw std::runtime_error("כותרת הגיבוי נקטעה");
        auto creds=credentialsFrom(header);master=UnlockCredentials(creds,code,"pin");if(master.empty())master=UnlockCredentials(creds,code,"pattern");
        if(master.empty())throw std::runtime_error("הקוד אינו מתאים לגיבוי");in.close();
        fs::path tail=root/L"tail.enc",zip=root/L"payload.zip";
        {std::ifstream original(src,std::ios::binary);original.seekg(12+n);std::ofstream out(tail,std::ios::binary);if(!original||!out)throw std::runtime_error("קריאת נתוני גיבוי נכשלה");out<<original.rdbuf();}
        DecryptStream(tail,zip,master,"vault-backup-payload");
        mz_zip_archive a{};if(!mz_zip_reader_init_file(&a,path8(zip).c_str(),0))throw std::runtime_error("קובץ הגיבוי מכיל ZIP פגום");
        bool hasIndex=false;std::uint64_t total=0;fs::path tempData=root/L"data";fs::create_directories(tempData/L"data");
        try{
            auto count=mz_zip_reader_get_num_files(&a);if(count>10002)throw std::runtime_error("בגיבוי יש יותר מדי פריטים");
            for(mz_uint i=0;i<count;++i){
                mz_zip_archive_file_stat st{};if(!mz_zip_reader_file_stat(&a,i,&st))throw std::runtime_error("רשומת ZIP פגומה");
                std::string name=st.m_filename;if(st.m_is_directory)throw std::runtime_error("הגיבוי מכיל תיקייה לא צפויה");
                fs::path target;
                if(name=="index.enc"){if(st.m_uncomp_size>32ULL*1024*1024)throw std::runtime_error("רשימת הקבצים גדולה מדי");target=root/L"index.enc";hasIndex=true;}
                else if(name.rfind("data/",0)==0&&name.size()>9&&name.substr(name.size()-4)==".bin"){
                    std::string file=name.substr(5),id=file.substr(0,file.size()-4);if(!isGuid(id))throw std::runtime_error("שם קובץ לא מורשה בגיבוי");
                    if(st.m_uncomp_size>8ULL*1024*1024*1024ULL||total+st.m_uncomp_size>8ULL*1024*1024*1024ULL)throw std::runtime_error("הגיבוי גדול מדי");
                    total+=st.m_uncomp_size;target=tempData/L"data"/toWide(file);
                } else throw std::runtime_error("הגיבוי מכיל נתיב לא מורשה");
                if(!mz_zip_reader_extract_to_file(&a,i,path8(target).c_str(),0))throw std::runtime_error("חילוץ קובץ מהגיבוי נכשל");
            }
        }catch(...){mz_zip_reader_end(&a);throw;}mz_zip_reader_end(&a);
        if(!hasIndex)throw std::runtime_error("רשימת הקבצים חסרה בגיבוי");
        auto indexEnc=readAll(root/L"index.enc",32*1024*1024);std::string aadText="vault-index";std::vector<std::uint8_t> aad(aadText.begin(),aadText.end());
        auto clear=OpenBytes(indexEnc,master,aad);auto j=json::parse(clear.begin(),clear.end());if(!j.is_array())throw std::runtime_error("רשימת הקבצים בגיבוי פגומה");
        for(auto& item:j)(void)itemFrom(item);if(!clear.empty())SecureZeroMemory(clear.data(),clear.size());
        fs::path old=dataDir;old+=L".old";std::error_code ec;fs::remove_all(old,ec);
        if(fs::exists(dataDir))fs::rename(dataDir,old);
        try{fs::rename(tempData/L"data",dataDir);}catch(...){if(fs::exists(old)&&!fs::exists(dataDir))fs::rename(old,dataDir);throw;}
        fs::remove_all(old,ec);atomicWrite(indexFile,indexEnc);SaveCredentials(creds);fs::remove_all(root,ec);return master;
    }catch(...){if(!master.empty())SecureZeroMemory(master.data(),master.size());std::error_code ec;fs::remove_all(root,ec);throw;}
}
}
