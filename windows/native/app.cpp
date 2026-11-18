#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <windowsx.h>
#include <objidl.h>
#include <commctrl.h>
#include <commdlg.h>
#include <gdiplus.h>
#include <algorithm>
#include <cmath>
#include <cwctype>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <map>
#include <optional>
#include <string>
#include <vector>
#include "vault_core.hpp"
#pragma comment(lib,"comctl32.lib")
#pragma comment(lib,"comdlg32.lib")
#pragma comment(lib,"gdiplus.lib")
#pragma comment(lib,"msimg32.lib")

namespace fs=std::filesystem;using namespace vault;
static constexpr wchar_t MC[]=L"VaultNativeMain",PC[]=L"VaultNativePattern",VC[]=L"VaultNativePreview";
static constexpr UINT WM_PATTERN=WM_APP+71;
static constexpr COLORREF BG=RGB(7,17,28),PANEL=RGB(18,29,42),PANEL2=RGB(27,42,58),ACC=RGB(255,49,90),TXT=RGB(241,244,247),MUTED=RGB(156,170,184),TEAL=RGB(89,211,221);
enum Page{SETUP,UNLOCK,HOME,SETTINGS,HISTORY,PIN_CHANGE,PATTERN_CHANGE,RESTORE};
enum Id{CREATE=1001,OPEN_PIN,SETUP_PAD,UNLOCK_PAD,IMPORT,BACKUP,RESTORE_BTN,SETTINGS_BTN,LOCK,
SEARCH,SORT,PREVIEW,EXPORT,DELETE_ITEM,SCARE,THRESHOLD,DEMO,PIN_BTN,PATTERN_BTN,HISTORY_BTN,BACK,
SAVE_PIN,SAVE_PATTERN,DO_RESTORE,CANCEL_RESTORE,CLEAR_HISTORY};
struct PatternState{std::vector<int> points;};
struct PreviewData{fs::path path;std::unique_ptr<Gdiplus::Bitmap> bitmap;};
static HINSTANCE inst{};static HWND mainH{},pin1{},pin2{},padH{},statusH{},listH{},searchH{},sortH{},codeH{};
static HFONT normalFont{},boldFont{};static HBRUSH bgBrush{},editBrush{},panelBrush{};
static std::map<int,HFONT> labelFonts;
static float uiScale=1.0f;static int uiCenter=545;
static Page page=UNLOCK;static std::unique_ptr<VaultStore> store;
static std::vector<VaultItem> items;static std::vector<size_t> visibleItems;static std::vector<uint8_t> master;
static std::wstring firstPattern,goodPattern;static fs::path backupPath;static ULONG_PTR gdip{};

static std::wstring W(const std::string&s){if(s.empty())return{};int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),(int)s.size(),nullptr,0);if(n<=0)return{};std::wstring o(n,L'\0');MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),(int)s.size(),o.data(),n);return o;}
static std::string U(const std::wstring&s){if(s.empty())return{};int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),(int)s.size(),nullptr,0,nullptr,nullptr);if(n<=0)return{};std::string o(n,'\0');WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),(int)s.size(),o.data(),n,nullptr,nullptr);return o;}
static std::wstring Get(HWND h){if(!h)return{};int n=GetWindowTextLengthW(h);std::wstring s(n+1,L'\0');GetWindowTextW(h,s.data(),n+1);s.resize(n);return s;}
static void clearControls(){EnumChildWindows(mainH,[](HWND h,LPARAM)->BOOL{DestroyWindow(h);return TRUE;},0);pin1=pin2=padH=statusH=listH=searchH=sortH=codeH=nullptr;}
static void metrics(float& scale,int& offsetY){RECT r{};GetClientRect(mainH,&r);int cw=(int)std::max(1L,r.right),ch=(int)std::max(1L,r.bottom);scale=std::min((cw-48)/1080.0f,(ch-48)/830.0f);scale=std::clamp(scale,0.65f,1.30f);offsetY=(int)std::lround((ch-830.0f*scale)/2.0f);uiScale=scale;}
static RECT mapRect(int x,int y,int w,int h){float s=1.0f;int oy=0;metrics(s,oy);RECT client{};GetClientRect(mainH,&client);RECT r{};r.left=(LONG)std::lround(client.right/2.0f+(x-uiCenter)*s);r.top=oy+(LONG)std::lround(y*s);r.right=r.left+(LONG)std::lround(w*s);r.bottom=r.top+(LONG)std::lround(h*s);return r;}
static void rememberRect(HWND h,int x,int y,int w,int he){SetPropW(h,L"LayoutX",(HANDLE)(INT_PTR)x);SetPropW(h,L"LayoutY",(HANDLE)(INT_PTR)y);SetPropW(h,L"LayoutW",(HANDLE)(INT_PTR)w);SetPropW(h,L"LayoutH",(HANDLE)(INT_PTR)he);RECT r=mapRect(x,y,w,he);SetWindowPos(h,nullptr,r.left,r.top,r.right-r.left,r.bottom-r.top,SWP_NOZORDER|SWP_NOACTIVATE);}
static void relayoutChild(HWND h){if(!GetPropW(h,L"LayoutW"))return;int x=(int)(INT_PTR)GetPropW(h,L"LayoutX"),y=(int)(INT_PTR)GetPropW(h,L"LayoutY"),w=(int)(INT_PTR)GetPropW(h,L"LayoutW"),he=(int)(INT_PTR)GetPropW(h,L"LayoutH");RECT r=mapRect(x,y,w,he);SetWindowPos(h,nullptr,r.left,r.top,r.right-r.left,r.bottom-r.top,SWP_NOZORDER|SWP_NOACTIVATE);}
static BOOL CALLBACK relayoutProc(HWND h,LPARAM){relayoutChild(h);return TRUE;}
static int pageCenter(){switch(page){case SETUP:return 690;case UNLOCK:return 720;case HOME:return 545;case SETTINGS:return 650;case HISTORY:return 650;case PIN_CHANGE:return 725;case PATTERN_CHANGE:return 625;case RESTORE:return 565;}return 545;}
static HFONT labelFont(int size,bool bold){int key=size*2+(bold?1:0);auto it=labelFonts.find(key);if(it!=labelFonts.end())return it->second;
 HFONT f=CreateFontW(-std::max(10,size),0,0,0,bold?FW_SEMIBOLD:FW_NORMAL,FALSE,FALSE,FALSE,HEBREW_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_SWISS,L"Segoe UI");labelFonts.emplace(key,f);return f;}
static HWND label(const std::wstring&t,int x,int y,int w,int h,int size=15,bool bold=false,COLORREF color=TXT,bool centered=false){
 HWND c=CreateWindowExW(WS_EX_RTLREADING,L"STATIC",t.c_str(),WS_CHILD|WS_VISIBLE|(centered?SS_CENTER:SS_RIGHT)|SS_NOPREFIX,x,y,w,h,mainH,nullptr,inst,nullptr);
 rememberRect(c,x,y,w,h);SendMessageW(c,WM_SETFONT,(WPARAM)labelFont(size,bold),TRUE);SetPropW(c,L"Color",(HANDLE)(INT_PTR)color);return c;}
static HWND edit(int id,int x,int y,int w,int h,bool secret=false,bool number=false){
 DWORD s=WS_CHILD|WS_VISIBLE|WS_TABSTOP|ES_AUTOHSCROLL|ES_RIGHT;if(secret)s|=ES_PASSWORD;if(number)s|=ES_NUMBER;
 HWND c=CreateWindowExW(WS_EX_CLIENTEDGE|WS_EX_RTLREADING,L"EDIT",L"",s,x,y,w,h,mainH,(HMENU)(INT_PTR)id,inst,nullptr);rememberRect(c,x,y,w,h);SendMessageW(c,WM_SETFONT,(WPARAM)normalFont,TRUE);return c;}
static HWND button(int id,const std::wstring&t,int x,int y,int w,int h,bool accent=false){
 HWND c=CreateWindowExW(WS_EX_RTLREADING,L"BUTTON",t.c_str(),WS_CHILD|WS_VISIBLE|WS_TABSTOP|BS_OWNERDRAW,x,y,w,h,mainH,(HMENU)(INT_PTR)id,inst,nullptr);
 rememberRect(c,x,y,w,h);SendMessageW(c,WM_SETFONT,(WPARAM)boldFont,TRUE);SetPropW(c,L"Accent",(HANDLE)(INT_PTR)(accent?1:0));return c;}
static HWND combo(int id,int x,int y,int w,int h){HWND c=CreateWindowExW(WS_EX_RTLREADING,L"COMBOBOX",L"",WS_CHILD|WS_VISIBLE|WS_TABSTOP|CBS_DROPDOWNLIST|WS_VSCROLL,x,y,w,h,mainH,(HMENU)(INT_PTR)id,inst,nullptr);rememberRect(c,x,y,w,h);SendMessageW(c,WM_SETFONT,(WPARAM)normalFont,TRUE);return c;}
static HWND pattern(int id,int x,int y,int w,int h){HWND c=CreateWindowExW(0,PC,L"",WS_CHILD|WS_VISIBLE|WS_TABSTOP,x,y,w,h,mainH,(HMENU)(INT_PTR)id,inst,nullptr);rememberRect(c,x,y,w,h);return c;}
static void header(const std::wstring&sub){label(L"כספת",uiCenter-175,16,350,40,29,true,TXT,true);label(sub,uiCenter-250,54,500,26,13,false,MUTED,true);label(L"●  מקומי ומוצפן",uiCenter-410,48,220,28,12,true,TEAL);}
static void message(const std::wstring&s,UINT icon=MB_ICONINFORMATION){MessageBoxW(mainH,s.c_str(),L"כספת",MB_OK|icon|MB_RTLREADING|MB_RIGHT);}
static void setMaster(std::vector<uint8_t> k){if(!master.empty())SecureZeroMemory(master.data(),master.size());master=std::move(k);}
static void clearSession(){if(!master.empty())SecureZeroMemory(master.data(),master.size());master.clear();items.clear();if(store){std::error_code ec;auto t=store->BaseDir()/L".tmp";if(fs::exists(t,ec))for(auto&e:fs::directory_iterator(t,ec))if(e.path().filename().wstring().rfind(L"preview-",0)==0)fs::remove(e.path(),ec);}}
static bool digits(const std::wstring&s){return !s.empty()&&std::all_of(s.begin(),s.end(),[](wchar_t c){return c>=L'0'&&c<=L'9';});}
static bool contains(std::wstring a,std::wstring b){std::transform(a.begin(),a.end(),a.begin(),towlower);std::transform(b.begin(),b.end(),b.begin(),towlower);return b.empty()||a.find(b)!=std::wstring::npos;}
static std::wstring localTime(int64_t ms){ULARGE_INTEGER u{};u.QuadPart=116444736000000000ULL+(uint64_t)ms*10000ULL;FILETIME f{u.LowPart,u.HighPart},l{};SYSTEMTIME t{};FileTimeToLocalFileTime(&f,&l);FileTimeToSystemTime(&l,&t);wchar_t b[64]{};swprintf_s(b,L"%02u/%02u/%04u  %02u:%02u:%02u",t.wDay,t.wMonth,t.wYear,t.wHour,t.wMinute,t.wSecond);return b;}
static void showSetup();static void showUnlock();static void showHome();static void showSettings();static void showHistory();static void showPinChange();static void showPatternChange();static void showRestore();static void attempt(const std::string&,const std::string&);static void refreshFiles();

static void showSetup(){clearControls();page=SETUP;uiCenter=pageCenter();firstPattern.clear();goodPattern.clear();header(L"הגדרה ראשונית");
 label(L"יצירת כספת חדשה",430,125,520,38,25,true);label(L"קבע קוד מספרי וקוד קווים. שניהם יפתחו את אותה כספת.",370,166,580,24,13,false,MUTED);
 label(L"קוד מספרי (4 עד 8 ספרות)",505,208,445,24,14,true);pin1=edit(0,505,236,445,38,true,true);
 label(L"אימות הקוד המספרי",505,284,445,24,14,true);pin2=edit(0,505,312,445,38,true,true);
 label(L"קוד קווים – צייר לפחות 4 נקודות, ואז אשר שוב",410,370,540,26,14,true);padH=pattern(SETUP_PAD,470,405,330,220);
 statusH=label(L"טרם הוגדר קוד קווים",415,635,535,24,13,false,TEAL);
 label(L"חשוב: אם תשכח את שני הקודים, לא ניתן לשחזר את הקבצים.",260,675,690,25,12,true,ACC);button(CREATE,L"יצירת הכספת",550,716,220,46,true);}
static void showUnlock(){clearControls();page=UNLOCK;uiCenter=pageCenter();if(!store->HasCredentials()){showSetup();return;}header(L"פתיחת הכספת");
 label(L"פתיחת הכספת",470,128,480,38,25,true);label(L"הזן את אחד הקודים האישיים כדי להמשיך",470,170,480,24,13,false,MUTED);label(L"הכספת נעולה וממתינה לאימות שלך",470,205,480,28,14,true,TEAL,true);
 auto s=store->LoadSettings();if(s.lockoutUntil>UnixMillisNow())label(L"הכספת נעולה זמנית. נסה שוב בעוד "+std::to_wstring(std::max<int64_t>(1,(s.lockoutUntil-UnixMillisNow())/1000))+L" שניות.",360,278,590,26,14,true,ACC);
 label(L"קוד מספרי",500,260,450,24,15,true);pin1=edit(0,500,290,450,40,true,true);button(OPEN_PIN,L"פתיחה עם קוד מספרי",615,340,220,44,true);
 label(L"קוד קווים",500,410,450,24,15,true);label(L"צייר את הקוד האישי שלך ברשת",500,438,450,22,12,false,MUTED);padH=pattern(UNLOCK_PAD,580,465,300,205);
 label(L"כל הנתונים נשמרים במחשב בלבד ומוצפנים.",400,690,550,24,12,false,MUTED);}
static void showHome(){if(master.empty()){showUnlock();return;}clearControls();page=HOME;uiCenter=pageCenter();header(L"המידע שלך, מוגן אצלך");
 label(L"הכספת שלך",540,125,410,36,25,true);label(std::to_wstring(items.size())+L" קבצים שמורים ומוצפנים במחשב",500,164,450,24,13,false,MUTED);
 button(IMPORT,L"+ הכנסת קבצים",790,205,160,45,true);button(BACKUP,L"גיבוי",620,205,145,45);button(RESTORE_BTN,L"שחזור",460,205,145,45);button(SETTINGS_BTN,L"הגדרות",300,205,145,45);button(LOCK,L"נעילה",140,205,145,45);
 label(L"הקבצים שלי",620,275,330,30,19,true);searchH=edit(SEARCH,140,315,525,38);label(L"חיפוש לפי שם קובץ",690,320,260,26,12,false,MUTED);
 sortH=combo(SORT,700,370,250,280);SendMessageW(sortH,CB_ADDSTRING,0,(LPARAM)L"תאריך");SendMessageW(sortH,CB_ADDSTRING,0,(LPARAM)L"שם");SendMessageW(sortH,CB_ADDSTRING,0,(LPARAM)L"גודל");SendMessageW(sortH,CB_SETCURSEL,0,0);
 label(L"מיון",850,372,100,22,12,false,MUTED);listH=CreateWindowExW(WS_EX_CLIENTEDGE|WS_EX_RTLREADING,L"LISTBOX",L"",WS_CHILD|WS_VISIBLE|WS_VSCROLL|LBS_NOTIFY|LBS_NOINTEGRALHEIGHT,140,415,810,235,mainH,(HMENU)5001,inst,nullptr);
 rememberRect(listH,140,415,810,235);SendMessageW(listH,WM_SETFONT,(WPARAM)normalFont,TRUE);button(PREVIEW,L"פתיחה / תצוגה",710,675,240,44,true);button(EXPORT,L"הוצאה",445,675,230,44);button(DELETE_ITEM,L"מחיקה",180,675,230,44);refreshFiles();}
static void refreshFiles(){if(!listH)return;SendMessageW(listH,LB_RESETCONTENT,0,0);visibleItems.clear();auto q=Get(searchH);std::vector<size_t> ids;
 for(size_t i=0;i<items.size();++i)if(contains(W(items[i].name),q))ids.push_back(i);int mode=sortH?(int)SendMessageW(sortH,CB_GETCURSEL,0,0):0;
 if(mode==1)std::sort(ids.begin(),ids.end(),[](auto a,auto b){return W(items[a].name)<W(items[b].name);});
 else if(mode==2)std::sort(ids.begin(),ids.end(),[](auto a,auto b){return items[a].size>items[b].size;});
 else std::sort(ids.begin(),ids.end(),[](auto a,auto b){return items[a].created>items[b].created;});
 for(auto i:ids){auto&x=items[i];std::wstring row=W(x.name)+L"  ·  "+W(FormatBytes(x.size))+L"  ·  "+W(x.mime);auto n=SendMessageW(listH,LB_ADDSTRING,0,(LPARAM)row.c_str());if(n!=LB_ERR&&n!=LB_ERRSPACE){SendMessageW(listH,LB_SETITEMDATA,n,(LPARAM)i);visibleItems.push_back(i);}}
 if(ids.empty()){std::wstring e=items.empty()?L"הכספת עדיין ריקה. לחץ על 'הכנסת קבצים' כדי להתחיל.":L"לא נמצאו קבצים מתאימים.";SendMessageW(listH,LB_ADDSTRING,0,(LPARAM)e.c_str());}}
static std::optional<size_t> selected(){if(!listH)return{};auto p=SendMessageW(listH,LB_GETCURSEL,0,0);if(p==LB_ERR)return{};auto i=SendMessageW(listH,LB_GETITEMDATA,p,0);if(i<0||(size_t)i>=items.size())return{};return(size_t)i;}
static void showSettings(){clearControls();page=SETTINGS;uiCenter=pageCenter();header(L"הגדרות");label(L"הגדרות",600,130,350,38,25,true);label(L"ניהול אבטחה והתנהגות הכספת",520,170,430,24,13,false,MUTED);
 label(L"אזהרה: אם תשכח את שני הקודים, לא ניתן לשחזר את הקבצים.",350,215,600,26,13,true,ACC);auto s=store->LoadSettings();
 button(SCARE,s.scareEnabled?L"מסך התרעה לאחר קוד שגוי: מופעל":L"מסך התרעה לאחר קוד שגוי: כבוי",475,270,475,44);
 label(L"סף הפעלת ההתרעה",650,330,300,24,14,true);sortH=combo(THRESHOLD,650,358,300,240);
 for(int i=1;i<=5;++i){auto t=std::to_wstring(i)+L" ניסיונות";SendMessageW(sortH,CB_ADDSTRING,0,(LPARAM)t.c_str());}
 SendMessageW(sortH,CB_SETCURSEL,std::clamp(s.scareThreshold,1,5)-1,0);
 button(DEMO,L"הדגמת מסך ההתרעה",475,420,475,42,true);button(PIN_BTN,L"שינוי קוד מספרי",475,475,475,42);button(PATTERN_BTN,L"שינוי קוד קווים",475,530,475,42);
 button(HISTORY_BTN,L"יומן ניסיונות כושלים",475,585,475,42);button(BACK,L"חזרה לקבצים",475,650,475,44);}
static void showHistory(){clearControls();page=HISTORY;uiCenter=pageCenter();header(L"יומן ניסיונות");label(L"יומן ניסיונות",570,130,380,38,25,true);label(L"היומן נשמר במחשב בלבד.",600,170,350,24,13,false,MUTED);
 HWND box=CreateWindowExW(WS_EX_CLIENTEDGE|WS_EX_RTLREADING,L"LISTBOX",L"",WS_CHILD|WS_VISIBLE|WS_VSCROLL|LBS_NOINTEGRALHEIGHT,380,220,570,370,mainH,(HMENU)5100,inst,nullptr);rememberRect(box,380,220,570,370);SendMessageW(box,WM_SETFONT,(WPARAM)normalFont,TRUE);
 auto s=store->LoadSettings();std::sort(s.attemptHistory.begin(),s.attemptHistory.end(),[](auto&a,auto&b){return a.time>b.time;});
 if(s.attemptHistory.empty())SendMessageW(box,LB_ADDSTRING,0,(LPARAM)L"אין ניסיונות כושלים שמורים.");
 for(auto&e:s.attemptHistory){auto row=L"ניסיון כושל  ·  "+localTime(e.time)+L"  ·  רצף ניסיונות: "+std::to_wstring(e.count);SendMessageW(box,LB_ADDSTRING,0,(LPARAM)row.c_str());}
 button(CLEAR_HISTORY,L"מחיקת יומן הניסיונות",655,625,295,42);button(BACK,L"חזרה להגדרות",355,625,280,42,true);}
static void showPinChange(){clearControls();page=PIN_CHANGE;uiCenter=pageCenter();header(L"שינוי קוד");label(L"שינוי קוד מספרי",540,145,410,38,25,true);label(L"קוד חדש בן 4 עד 8 ספרות",500,220,450,24,14,true);pin1=edit(0,500,250,450,38,true,true);label(L"הקלד שוב את הקוד החדש",500,310,450,24,14,true);pin2=edit(0,500,340,450,38,true,true);button(SAVE_PIN,L"שמירת הקוד החדש",610,415,230,44,true);button(BACK,L"חזרה להגדרות",610,470,230,42);}
static void showPatternChange(){clearControls();page=PATTERN_CHANGE;uiCenter=pageCenter();firstPattern.clear();goodPattern.clear();header(L"שינוי קוד קווים");label(L"שינוי קוד קווים",540,130,410,38,25,true);label(L"צייר קוד חדש ואז צייר שוב לאימות. נדרשות לפחות 4 נקודות.",300,174,650,26,13,false,MUTED);
 padH=pattern(SETUP_PAD,500,225,320,280);statusH=label(L"צייר לפחות 4 נקודות",470,525,480,24,13,false,TEAL);button(SAVE_PATTERN,L"שמירת קוד קווים חדש",600,585,260,44,true);button(BACK,L"חזרה להגדרות",600,640,260,42);}
static void showRestore(){clearControls();page=RESTORE;uiCenter=pageCenter();header(L"שחזור מגיבוי");label(L"שחזור מגיבוי",540,145,410,38,25,true);label(L"קובץ הגיבוי שנבחר:",500,220,450,24,14,true);label(backupPath.wstring(),180,250,770,30,12,false,TEAL);
 label(L"הזן את הקוד ששימש ליצירת הגיבוי",400,315,550,24,14,true);codeH=edit(0,500,345,450,40,true);button(DO_RESTORE,L"שחזור הכספת",610,425,240,44,true);button(CANCEL_RESTORE,L"ביטול",610,480,240,42);}
static void scare(){MessageBoxW(mainH,L"מרכז אבטחה מקומי\n\nנרשם ניסיון גישה לא מורשה.\nהמידע נשמר מקומית בלבד.\n\nהדמיה בלבד — לא נשלח דיווח ולא מתבצע צילום בגרסת Windows.",L"כספת",MB_OK|MB_ICONWARNING|MB_RTLREADING|MB_RIGHT);}
static void attempt(const std::string&code,const std::string&type){auto s=store->LoadSettings();if(s.lockoutUntil>UnixMillisNow()){message(L"הכספת נעולה זמנית. יש להמתין לפני ניסיון נוסף.",MB_ICONWARNING);showUnlock();return;}if(code.empty()){message(L"יש להזין קוד.",MB_ICONWARNING);return;}
 try{auto c=store->LoadCredentials();auto key=UnlockCredentials(c,code,type);if(key.empty()){auto fail=store->RecordFailure();auto a=store->LoadSettings();if(a.scareEnabled&&fail.count%std::clamp(a.scareThreshold,1,5)==0)scare();
 if(fail.count>=5){int level=std::clamp(a.lockoutLevel,0,6);a.lockoutUntil=UnixMillisNow()+30000LL*(1LL<<level);a.lockoutLevel=std::min(level+1,6);a.consecutiveFailures=0;store->SaveSettings(a);message(L"יותר מדי ניסיונות. הכספת ננעלה לזמן קצר.",MB_ICONWARNING);}
 else message(L"קוד שגוי.",MB_ICONWARNING);showUnlock();return;}auto loaded=store->LoadIndex(key);store->ResetConsecutiveFailures();auto a=store->LoadSettings();a.lockoutUntil=0;store->SaveSettings(a);setMaster(std::move(key));items=std::move(loaded);showHome();}
 catch(const std::exception&e){message(L"לא ניתן לפתוח את הכספת: "+W(e.what()),MB_ICONERROR);showUnlock();}}
static std::vector<fs::path> pickFiles(){std::vector<wchar_t>b(65536,L'\0');wchar_t filter[]=L"כל הקבצים (*.*)\0*.*\0\0";OPENFILENAMEW f{};f.lStructSize=sizeof(f);f.hwndOwner=mainH;f.lpstrFilter=filter;f.lpstrFile=b.data();f.nMaxFile=(DWORD)b.size();
 f.Flags=OFN_EXPLORER|OFN_FILEMUSTEXIST|OFN_PATHMUSTEXIST|OFN_ALLOWMULTISELECT|OFN_HIDEREADONLY;f.lpstrTitle=L"הכנסת קבצים לכספת";if(!GetOpenFileNameW(&f))return{};
 std::vector<fs::path>out;std::wstring first=b.data();const wchar_t*p=b.data()+first.size()+1;if(!*p)out.emplace_back(first);else{fs::path dir(first);while(*p){std::wstring n=p;out.push_back(dir/n);p+=n.size()+1;}}return out;}
static fs::path pickBackup(){wchar_t file[MAX_PATH]{};wchar_t filter[]=L"גיבוי כספת (*.vault)\0*.vault\0כל הקבצים (*.*)\0*.*\0\0";OPENFILENAMEW f{};f.lStructSize=sizeof(f);f.hwndOwner=mainH;f.lpstrFilter=filter;f.lpstrFile=file;f.nMaxFile=MAX_PATH;f.Flags=OFN_EXPLORER|OFN_FILEMUSTEXIST|OFN_PATHMUSTEXIST;f.lpstrTitle=L"בחירת גיבוי";return GetOpenFileNameW(&f)?fs::path(file):fs::path{};}
static fs::path pickSave(const std::wstring&name,const std::wstring&title,const wchar_t*filter){wchar_t file[MAX_PATH]{};wcscpy_s(file,name.c_str());OPENFILENAMEW f{};f.lStructSize=sizeof(f);f.hwndOwner=mainH;f.lpstrFilter=filter;f.lpstrFile=file;f.nMaxFile=MAX_PATH;f.Flags=OFN_EXPLORER|OFN_PATHMUSTEXIST|OFN_OVERWRITEPROMPT;f.lpstrTitle=title.c_str();return GetSaveFileNameW(&f)?fs::path(file):fs::path{};}
static void importFiles(){auto paths=pickFiles();if(paths.empty()||master.empty())return;auto updated=items;size_t n=0;try{for(auto&p:paths){updated.push_back(store->ImportFile(p,master));++n;}store->SaveIndex(updated,master);items=std::move(updated);showHome();message(L"יובאו "+std::to_wstring(n)+L" קבצים לכספת.");}catch(const std::exception&e){message(L"הכנסת הקבצים נכשלה: "+W(e.what()),MB_ICONERROR);}}
static void exportFile(){auto i=selected();if(!i){message(L"יש לבחור קובץ מהרשימה.",MB_ICONWARNING);return;}auto&p=items[*i];auto dest=pickSave(W(p.name),L"הוצאת קובץ מהכספת",L"כל הקבצים (*.*)\0*.*\0\0");if(dest.empty())return;try{store->ExportFile(p,dest,master);message(L"הקובץ יוצא בהצלחה.");}catch(const std::exception&e){message(L"הוצאת הקובץ נכשלה: "+W(e.what()),MB_ICONERROR);}}
static LRESULT CALLBACK PreviewProc(HWND h,UINT m,WPARAM w,LPARAM l){auto*d=(PreviewData*)GetWindowLongPtrW(h,GWLP_USERDATA);if(m==WM_NCCREATE){auto*c=(CREATESTRUCTW*)l;SetWindowLongPtrW(h,GWLP_USERDATA,(LONG_PTR)c->lpCreateParams);return 0;}
 if(m==WM_PAINT){PAINTSTRUCT p{};HDC dc=BeginPaint(h,&p);RECT r{};GetClientRect(h,&r);HBRUSH b=CreateSolidBrush(BG);FillRect(dc,&r,b);DeleteObject(b);if(d&&d->bitmap){Gdiplus::Graphics g(dc);g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);UINT x=d->bitmap->GetWidth(),y=d->bitmap->GetHeight();double k=std::min((r.right-30.0)/std::max(1U,x),(r.bottom-30.0)/std::max(1U,y));int dw=(int)(x*k),dh=(int)(y*k);g.DrawImage(d->bitmap.get(),(r.right-dw)/2,(r.bottom-dh)/2,dw,dh);}EndPaint(h,&p);return 0;}
 if(m==WM_NCDESTROY){if(d){std::error_code ec;fs::remove(d->path,ec);delete d;}SetWindowLongPtrW(h,GWLP_USERDATA,0);return 0;}return DefWindowProcW(h,m,w,l);}
static void previewFile(){auto i=selected();if(!i){message(L"יש לבחור קובץ מהרשימה.",MB_ICONWARNING);return;}auto item=items[*i];try{auto temp=store->DecryptToTemp(item,master);
 if(item.mime.rfind("image/",0)==0){auto*d=new PreviewData();d->path=temp;d->bitmap=std::make_unique<Gdiplus::Bitmap>(temp.c_str());if(d->bitmap->GetLastStatus()!=Gdiplus::Ok){std::error_code ec;fs::remove(temp,ec);delete d;throw std::runtime_error("לא ניתן לקרוא את התמונה");}
 HWND w=CreateWindowExW(WS_EX_APPWINDOW,VC,W(item.name).c_str(),WS_OVERLAPPEDWINDOW|WS_VISIBLE,CW_USEDEFAULT,CW_USEDEFAULT,900,720,mainH,nullptr,inst,d);if(!w){std::error_code ec;fs::remove(temp,ec);delete d;}}
 else if(item.mime.rfind("text/",0)==0||item.mime.find("json")!=std::string::npos||item.mime.find("xml")!=std::string::npos){std::ifstream f(temp,std::ios::binary);std::string s((std::istreambuf_iterator<char>(f)),{});if(s.size()>400000)s.resize(400000);std::error_code ec;fs::remove(temp,ec);HWND w=CreateWindowExW(WS_EX_CLIENTEDGE|WS_EX_RTLREADING,L"EDIT",W(s).c_str(),WS_OVERLAPPEDWINDOW|WS_VISIBLE|ES_MULTILINE|ES_READONLY|ES_AUTOVSCROLL|WS_VSCROLL,CW_USEDEFAULT,CW_USEDEFAULT,900,700,mainH,nullptr,inst,nullptr);if(w)SendMessageW(w,WM_SETFONT,(WPARAM)normalFont,TRUE);}
 else{std::error_code ec;fs::remove(temp,ec);message(L"סוג הקובץ הזה ניתן להוצאה בלבד.");}}
 catch(const std::exception&e){message(L"לא ניתן לפתוח את הקובץ: "+W(e.what()),MB_ICONERROR);}}
static void deleteFile(){auto i=selected();if(!i){message(L"יש לבחור קובץ מהרשימה.",MB_ICONWARNING);return;}if(MessageBoxW(mainH,L"הקובץ יימחק מהכספת ולא יהיה אפשר לשחזר אותו. להמשיך?",L"כספת",MB_YESNO|MB_ICONWARNING|MB_RTLREADING|MB_RIGHT)!=IDYES)return;
 try{auto v=items;store->Delete(v[*i]);v.erase(v.begin()+*i);store->SaveIndex(v,master);items=std::move(v);showHome();}catch(const std::exception&e){message(L"מחיקת הקובץ נכשלה: "+W(e.what()),MB_ICONERROR);}}
static void backup(){auto dest=pickSave(L"vault-backup.vault",L"יצירת גיבוי מוצפן",L"גיבוי כספת (*.vault)\0*.vault\0\0");if(dest.empty())return;try{store->WriteBackup(dest,master);message(L"הגיבוי המוצפן נוצר בהצלחה.");}catch(const std::exception&e){message(L"יצירת הגיבוי נכשלה: "+W(e.what()),MB_ICONERROR);}}
static void beginRestore(){auto p=pickBackup();if(p.empty())return;if(MessageBoxW(mainH,L"השחזור יחליף את כל תוכן הכספת הנוכחית בתוכן הגיבוי. להמשיך?",L"כספת",MB_YESNO|MB_ICONWARNING|MB_RTLREADING|MB_RIGHT)!=IDYES)return;backupPath=p;showRestore();}
static void doRestore(){auto code=U(Get(codeH));if(code.empty()){message(L"יש להזין קוד.",MB_ICONWARNING);return;}try{auto k=store->RestoreBackup(backupPath,code);auto v=store->LoadIndex(k);setMaster(std::move(k));items=std::move(v);store->ResetConsecutiveFailures();message(L"הגיבוי שוחזר בהצלחה.");showHome();}catch(const std::exception&e){message(L"השחזור נכשל: "+W(e.what()),MB_ICONERROR);showRestore();}}
static void savePin(){auto a=Get(pin1),b=Get(pin2);if(a.size()<4||a.size()>8||!digits(a)||a!=b){message(L"הקוד אינו תקין או שהאימות אינו תואם.",MB_ICONWARNING);return;}try{auto c=store->LoadCredentials();store->SaveCredentials(ReplaceCredential(c,master,U(a),"pin"));message(L"הקוד המספרי עודכן. קוד הקווים נשאר ללא שינוי.");showSettings();}catch(const std::exception&e){message(L"עדכון הקוד נכשל: "+W(e.what()),MB_ICONERROR);}}
static void savePattern(){if(goodPattern.size()<4){message(L"יש להשלים אימות של לפחות 4 נקודות.",MB_ICONWARNING);return;}try{auto c=store->LoadCredentials();store->SaveCredentials(ReplaceCredential(c,master,U(goodPattern),"pattern"));message(L"קוד הקווים עודכן. הקוד המספרי נשאר ללא שינוי.");showSettings();}catch(const std::exception&e){message(L"עדכון קוד הקווים נכשל: "+W(e.what()),MB_ICONERROR);}}
static void patternDone(const std::wstring&v){if(page==SETUP||page==PATTERN_CHANGE){if(v.size()<4){SetWindowTextW(statusH,L"יש לצייר לפחות 4 נקודות.");return;}if(firstPattern.empty()){firstPattern=v;SetWindowTextW(statusH,L"כעת צייר שוב את אותו הקוד לאימות.");}
 else if(firstPattern==v){goodPattern=v;SetWindowTextW(statusH,L"קוד הקווים אומת בהצלחה.");}else{firstPattern=v;goodPattern.clear();SetWindowTextW(statusH,L"הקודים אינם תואמים. צייר שוב לאימות.");}}
 else if(page==UNLOCK&&v.size()>=4)attempt(U(v),"pattern");}
static void createVault(){auto a=Get(pin1),b=Get(pin2);if(a.size()<4||a.size()>8||!digits(a)){message(L"הקוד המספרי חייב להכיל 4 עד 8 ספרות.",MB_ICONWARNING);return;}if(a!=b){message(L"הקודים המספריים אינם תואמים.",MB_ICONWARNING);return;}if(goodPattern.size()<4){message(L"יש להגדיר ולאמת קוד קווים של לפחות 4 נקודות.",MB_ICONWARNING);return;}
 auto k=RandomBytes(32);try{store->SaveCredentials(CreateCredentials(k,U(a),U(goodPattern)));store->SaveIndex({},k);store->SaveSettings(SettingsDocument{});setMaster(std::move(k));items.clear();showHome();}catch(const std::exception&e){if(!k.empty())SecureZeroMemory(k.data(),k.size());message(L"יצירת הכספת נכשלה: "+W(e.what()),MB_ICONERROR);}}

static LRESULT CALLBACK PatternProc(HWND h,UINT m,WPARAM w,LPARAM l){auto*s=(PatternState*)GetWindowLongPtrW(h,GWLP_USERDATA);if(m==WM_NCCREATE){s=new PatternState();SetWindowLongPtrW(h,GWLP_USERDATA,(LONG_PTR)s);}
 auto hit=[&](int mx,int my){RECT r{};GetClientRect(h,&r);int side=std::min(r.bottom,r.right)-18,g=side/4,left=(r.right-side)/2,top=(r.bottom-side)/2;double best=g*.62;int got=-1;
 for(int i=0;i<9;++i){int x=left+g+(i%3)*g,y=top+g+(i/3)*g;double d=std::hypot((double)(mx-x),(double)(my-y));if(d<=best){best=d;got=i;}}
 if(got<0||!s||std::find(s->points.begin(),s->points.end(),got)!=s->points.end())return;
 if(!s->points.empty()){int p=s->points.back(),dr=got/3-p/3,dc=got%3-p%3,a=abs(dr),b=abs(dc);while(b){int t=a%b;a=b;b=t;}int n=std::max(1,a);for(int j=1;j<n;++j){int mid=(p/3+dr/n*j)*3+p%3+dc/n*j;if(std::find(s->points.begin(),s->points.end(),mid)==s->points.end())s->points.push_back(mid);}}
 s->points.push_back(got);};
 if(m==WM_ERASEBKGND)return 1;
 if(m==WM_PAINT){PAINTSTRUCT ps{};HDC dc=BeginPaint(h,&ps);RECT r{};GetClientRect(h,&r);int side=std::min(r.bottom,r.right)-18,g=side/4,left=(r.right-side)/2,top=(r.bottom-side)/2;
 if(s&&s->points.size()>1){HPEN p=CreatePen(PS_SOLID,4,ACC);auto old=SelectObject(dc,p);int n=s->points[0];MoveToEx(dc,left+g+(n%3)*g,top+g+(n/3)*g,nullptr);for(size_t j=1;j<s->points.size();++j){n=s->points[j];LineTo(dc,left+g+(n%3)*g,top+g+(n/3)*g);}SelectObject(dc,old);DeleteObject(p);}
 for(int i=0;i<9;++i){int x=left+g+(i%3)*g,y=top+g+(i/3)*g;bool chosen=s&&std::find(s->points.begin(),s->points.end(),i)!=s->points.end();int radius=std::max(7,(int)std::lround(g*0.16));HBRUSH b=CreateSolidBrush(chosen?ACC:PANEL2);HPEN p=CreatePen(PS_SOLID,2,chosen?RGB(255,210,220):MUTED);auto ob=SelectObject(dc,b),op=SelectObject(dc,p);Ellipse(dc,x-radius,y-radius,x+radius,y+radius);SelectObject(dc,ob);SelectObject(dc,op);DeleteObject(b);DeleteObject(p);}EndPaint(h,&ps);return 0;}
 if(m==WM_LBUTTONDOWN){if(s)s->points.clear();hit(GET_X_LPARAM(l),GET_Y_LPARAM(l));SetCapture(h);InvalidateRect(h,nullptr,TRUE);return 0;}
 if(m==WM_MOUSEMOVE&&(w&MK_LBUTTON)){hit(GET_X_LPARAM(l),GET_Y_LPARAM(l));InvalidateRect(h,nullptr,TRUE);return 0;}
 if(m==WM_LBUTTONUP){ReleaseCapture();if(s){std::wstring v;for(int p:s->points)v.push_back((wchar_t)(L'0'+p));if(v.size()>=4)SendMessageW(GetParent(h),WM_PATTERN,(WPARAM)GetDlgCtrlID(h),(LPARAM)&v);s->points.clear();}InvalidateRect(h,nullptr,TRUE);return 0;}
 if(m==WM_NCDESTROY){delete s;SetWindowLongPtrW(h,GWLP_USERDATA,0);}return DefWindowProcW(h,m,w,l);}
static void drawButton(const DRAWITEMSTRUCT*d){bool a=(INT_PTR)GetPropW(d->hwndItem,L"Accent")==1;HDC dc=d->hDC;RECT r=d->rcItem;HBRUSH b=CreateSolidBrush(a?ACC:PANEL2);HPEN p=CreatePen(PS_SOLID,1,a?ACC:RGB(87,119,148));auto ob=SelectObject(dc,b),op=SelectObject(dc,p);RoundRect(dc,r.left,r.top,r.right,r.bottom,15,15);SelectObject(dc,ob);SelectObject(dc,op);DeleteObject(b);DeleteObject(p);SetBkMode(dc,TRANSPARENT);SetTextColor(dc,TXT);auto f=SelectObject(dc,boldFont);wchar_t t[512]{};GetWindowTextW(d->hwndItem,t,512);DrawTextW(dc,t,-1,&r,DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_RTLREADING|DT_NOPREFIX);SelectObject(dc,f);if(d->itemState&ODS_FOCUS){InflateRect(&r,-5,-5);DrawFocusRect(dc,&r);}}
static LRESULT CALLBACK MainProc(HWND h,UINT m,WPARAM w,LPARAM l){
 if(m==WM_CREATE){mainH=h;bgBrush=CreateSolidBrush(BG);panelBrush=CreateSolidBrush(PANEL);editBrush=CreateSolidBrush(RGB(10,22,34));INITCOMMONCONTROLSEX icc{sizeof(icc),ICC_STANDARD_CLASSES};InitCommonControlsEx(&icc);
 WNDCLASSW p{};p.lpfnWndProc=PatternProc;p.hInstance=inst;p.lpszClassName=PC;p.hCursor=LoadCursorW(nullptr,IDC_CROSS);RegisterClassW(&p);
 WNDCLASSW v{};v.lpfnWndProc=PreviewProc;v.hInstance=inst;v.lpszClassName=VC;v.hCursor=LoadCursorW(nullptr,IDC_ARROW);RegisterClassW(&v);
 try{store=std::make_unique<VaultStore>();if(store->HasCredentials())showUnlock();else showSetup();}catch(const std::exception&e){message(L"לא ניתן לאתחל את הכספת: "+W(e.what()),MB_ICONERROR);}return 0;}
 if(m==WM_PATTERN){auto*p=(std::wstring*)l;if(p)patternDone(*p);return 0;}
 if(m==WM_DRAWITEM){auto*d=(DRAWITEMSTRUCT*)l;if(d&&d->CtlType==ODT_BUTTON){drawButton(d);return TRUE;}}
 if(m==WM_CTLCOLORSTATIC){HDC dc=(HDC)w;COLORREF c=TXT;HANDLE t=GetPropW((HWND)l,L"Color");if(t)c=(COLORREF)(INT_PTR)t;SetTextColor(dc,c);SetBkMode(dc,TRANSPARENT);return(HBRUSH)GetStockObject(HOLLOW_BRUSH);}
 if(m==WM_CTLCOLOREDIT||m==WM_CTLCOLORLISTBOX){HDC dc=(HDC)w;SetTextColor(dc,TXT);SetBkColor(dc,RGB(10,22,34));return(LRESULT)editBrush;}
 if(m==WM_ERASEBKGND){HDC dc=(HDC)w;RECT r{};GetClientRect(h,&r);HBRUSH b=CreateSolidBrush(BG);FillRect(dc,&r,b);DeleteObject(b);return 1;}
 if(m==WM_SIZE&&w!=SIZE_MINIMIZED){EnumChildWindows(h,relayoutProc,0);InvalidateRect(h,nullptr,TRUE);return 0;}
 if(m==WM_PAINT){PAINTSTRUCT ps{};HDC dc=BeginPaint(h,&ps);RECT r{};GetClientRect(h,&r);
 Gdiplus::Graphics g(dc);g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
 Gdiplus::Rect bounds(0,0,std::max(1L,r.right),std::max(1L,r.bottom));
 Gdiplus::LinearGradientBrush backdrop(bounds,Gdiplus::Color(255,10,23,38),Gdiplus::Color(255,7,13,24),Gdiplus::LinearGradientModeVertical);g.FillRectangle(&backdrop,bounds);
 Gdiplus::GraphicsPath tealGlowPath;tealGlowPath.AddEllipse((Gdiplus::REAL)(r.right-430),(Gdiplus::REAL)-180,520.0f,520.0f);
 Gdiplus::PathGradientBrush tealGlow(&tealGlowPath);Gdiplus::Color tealCenter(34,36,215,231),tealEdge(0,36,215,231);tealGlow.SetCenterColor(tealCenter);tealGlow.SetSurroundColor(tealEdge);g.FillPath(&tealGlow,&tealGlowPath);
 Gdiplus::GraphicsPath roseGlowPath;roseGlowPath.AddEllipse(-190.0f,(Gdiplus::REAL)(r.bottom-360),430.0f,430.0f);
 Gdiplus::PathGradientBrush roseGlow(&roseGlowPath);Gdiplus::Color roseCenter(28,255,49,90),roseEdge(0,255,49,90);roseGlow.SetCenterColor(roseCenter);roseGlow.SetSurroundColor(roseEdge);g.FillPath(&roseGlow,&roseGlowPath);
 float s=1.0f;int oy=0;metrics(s,oy);
 auto panel=[&](int x,int y,int w,int h){RECT q=mapRect(x,y,w,h);Gdiplus::Rect pr(q.left,q.top,q.right-q.left,q.bottom-q.top);if(pr.Width<=0||pr.Height<=0)return;
   Gdiplus::GraphicsPath path;Gdiplus::REAL rad=18.0f*s,diam=rad*2;
   path.AddArc(pr.X,pr.Y,diam,diam,180,90);path.AddArc(pr.GetRight()-diam,pr.Y,diam,diam,270,90);
   path.AddArc(pr.GetRight()-diam,pr.GetBottom()-diam,diam,diam,0,90);path.AddArc(pr.X,pr.GetBottom()-diam,diam,diam,90,90);path.CloseFigure();
   Gdiplus::LinearGradientBrush fill(pr,Gdiplus::Color(246,25,39,55),Gdiplus::Color(246,16,27,40),Gdiplus::LinearGradientModeForwardDiagonal);
   Gdiplus::Pen border(Gdiplus::Color(105,100,130,151),std::max(1.0f,s));g.FillPath(&fill,&path);g.DrawPath(&border,&path);
 };
 auto badge=[&](int x,int y,int w,int h){RECT q=mapRect(x,y,w,h);Gdiplus::Rect pr(q.left,q.top,q.right-q.left,q.bottom-q.top);Gdiplus::GraphicsPath path;Gdiplus::REAL rad=(pr.Height/2.0f),diam=rad*2;
   path.AddArc(pr.X,pr.Y,diam,diam,90,180);path.AddArc(pr.GetRight()-diam,pr.Y,diam,diam,270,180);path.CloseFigure();
   Gdiplus::SolidBrush fill(Gdiplus::Color(48,57,211,221));Gdiplus::Pen edge(Gdiplus::Color(110,90,211,221),1.0f);g.FillPath(&fill,&path);g.DrawPath(&edge,&path);
 };
 badge(uiCenter-410,46,220,30);
 if(page==UNLOCK){
   panel(470,245,500,155);
   panel(470,405,500,285);
 }else if(page==SETUP){
   panel(350,110,640,680);
 }else if(page==HOME){
   panel(120,105,850,650);
 }else if(page==SETTINGS||page==HISTORY){
   panel(350,110,600,650);
 }else if(page==PIN_CHANGE||page==PATTERN_CHANGE||page==RESTORE){
   panel(350,110,600,600);
 }
 EndPaint(h,&ps);return 0;}
 if(m==WM_COMMAND){int id=LOWORD(w),code=HIWORD(w);if(id==SEARCH&&code==EN_CHANGE){refreshFiles();return 0;}if(id==SORT&&code==CBN_SELCHANGE){refreshFiles();return 0;}
 if(id==THRESHOLD&&code==CBN_SELCHANGE){auto s=store->LoadSettings();s.scareThreshold=(int)SendMessageW(sortH,CB_GETCURSEL,0,0)+1;store->SaveSettings(s);return 0;}
 switch(id){case CREATE:createVault();return 0;case OPEN_PIN:attempt(U(Get(pin1)),"pin");return 0;case IMPORT:importFiles();return 0;case BACKUP:backup();return 0;case RESTORE_BTN:beginRestore();return 0;case SETTINGS_BTN:showSettings();return 0;case LOCK:clearSession();showUnlock();return 0;case PREVIEW:previewFile();return 0;case EXPORT:exportFile();return 0;case DELETE_ITEM:deleteFile();return 0;
 case SCARE:{auto s=store->LoadSettings();s.scareEnabled=!s.scareEnabled;store->SaveSettings(s);showSettings();return 0;}case DEMO:scare();return 0;case PIN_BTN:showPinChange();return 0;case PATTERN_BTN:showPatternChange();return 0;case HISTORY_BTN:showHistory();return 0;
 case BACK:if(page==HISTORY||page==PIN_CHANGE||page==PATTERN_CHANGE)showSettings();else showHome();return 0;case SAVE_PIN:savePin();return 0;case SAVE_PATTERN:savePattern();return 0;case DO_RESTORE:doRestore();return 0;case CANCEL_RESTORE:showHome();return 0;
 case CLEAR_HISTORY:if(MessageBoxW(h,L"כל רשומות הניסיונות יימחקו לצמיתות. להמשיך?",L"כספת",MB_YESNO|MB_ICONWARNING|MB_RTLREADING|MB_RIGHT)==IDYES){store->ClearAttemptHistory();showHistory();}return 0;}}
 if(m==WM_SIZE&&w==SIZE_MINIMIZED&&!master.empty()){clearSession();return 0;}
 if(m==WM_CLOSE){clearSession();DestroyWindow(h);return 0;}
 if(m==WM_DESTROY){clearSession();if(bgBrush)DeleteObject(bgBrush);if(panelBrush)DeleteObject(panelBrush);if(editBrush)DeleteObject(editBrush);if(normalFont)DeleteObject(normalFont);if(boldFont)DeleteObject(boldFont);for(auto&f:labelFonts)if(f.second)DeleteObject(f.second);labelFonts.clear();PostQuitMessage(0);return 0;}
 return DefWindowProcW(h,m,w,l);}
int WINAPI wWinMain(HINSTANCE h,HINSTANCE,PWSTR,int show){inst=h;Gdiplus::GdiplusStartupInput in;Gdiplus::GdiplusStartup(&gdip,&in,nullptr);
 normalFont=CreateFontW(-18,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,HEBREW_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_SWISS,L"Segoe UI");
 boldFont=CreateFontW(-19,0,0,0,FW_SEMIBOLD,FALSE,FALSE,FALSE,HEBREW_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_SWISS,L"Segoe UI");
 WNDCLASSW wc{};wc.lpfnWndProc=MainProc;wc.hInstance=h;wc.lpszClassName=MC;wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);wc.hIcon=LoadIconW(nullptr,IDI_APPLICATION);
 if(!RegisterClassW(&wc))return 1;HWND wnd=CreateWindowExW(WS_EX_APPWINDOW,MC,L"כספת",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,CW_USEDEFAULT,CW_USEDEFAULT,1080,830,nullptr,nullptr,h,nullptr);
 if(!wnd)return 2;ShowWindow(wnd,show);UpdateWindow(wnd);MSG msg{};while(GetMessageW(&msg,nullptr,0,0)>0){TranslateMessage(&msg);DispatchMessageW(&msg);}if(gdip)Gdiplus::GdiplusShutdown(gdip);return(int)msg.wParam;}
