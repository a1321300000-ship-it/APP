using Microsoft.Win32;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Vault.Windows;

public partial class MainWindow : Window
{
    private static readonly Brush Bg = new SolidColorBrush(Color.FromRgb(5, 5, 5));
    private static readonly Brush PanelBrush = new SolidColorBrush(Color.FromRgb(17, 19, 21));
    private static readonly Brush AccentBrush = new SolidColorBrush(Color.FromRgb(255, 23, 68));
    private static readonly Brush WhiteBrush = new SolidColorBrush(Color.FromRgb(232, 236, 231));
    private static readonly Brush MutedBrush = new SolidColorBrush(Color.FromRgb(167, 176, 165));
    private readonly VaultStore store = new();
    private byte[]? masterKey;
    private List<VaultItem> items = [];
    private string sortMode = "date";
    private string searchText = "";
    private bool relockOnRestore;

    public MainWindow()
    {
        InitializeComponent();
        StateChanged += HandleWindowStateChanged;
        Closed += HandleClosed;
        if (store.HasCredentials()) ShowUnlock();
        else ShowSetup();
    }

    private static TextBlock Text(string value, double size = 15, Brush? color = null, bool bold = false)
    {
        return new TextBlock
        {
            Text = value,
            FontSize = size,
            Foreground = color ?? MutedBrush,
            FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
            TextAlignment = TextAlignment.Right,
            TextWrapping = TextWrapping.Wrap,
            FlowDirection = FlowDirection.RightToLeft,
            Margin = new Thickness(3, 4, 3, 4)
        };
    }

    private static Border Card(params UIElement[] children)
    {
        var stack = new StackPanel { FlowDirection = FlowDirection.RightToLeft };
        foreach (var child in children) stack.Children.Add(child);
        return new Border
        {
            Background = PanelBrush,
            BorderBrush = new SolidColorBrush(Color.FromArgb(102, 63, 37, 43)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(15),
            Padding = new Thickness(16),
            Margin = new Thickness(4, 7, 4, 7),
            Child = stack
        };
    }

    private static Button Button(string label, Action action, bool accent = false)
    {
        var button = new Button
        {
            Content = label,
            MinHeight = 40,
            Background = accent ? AccentBrush : new SolidColorBrush(Color.FromRgb(26, 29, 29)),
            Foreground = accent ? Bg : WhiteBrush,
            BorderBrush = accent ? AccentBrush : new SolidColorBrush(Color.FromArgb(153, 255, 23, 68)),
            HorizontalContentAlignment = HorizontalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        button.Click += (_, _) => action();
        return button;
    }

    private static TextBox TextField(string initial = "", string placeholder = "")
    {
        return new TextBox
        {
            Text = initial,
            ToolTip = placeholder,
            Height = 42,
            VerticalContentAlignment = VerticalAlignment.Center,
            FlowDirection = FlowDirection.LeftToRight,
            TextAlignment = TextAlignment.Center
        };
    }

    private static PasswordBox SecretField(string placeholder = "")
    {
        return new PasswordBox
        {
            ToolTip = placeholder,
            Height = 42,
            VerticalContentAlignment = VerticalAlignment.Center,
            FlowDirection = FlowDirection.LeftToRight
        };
    }

    private StackPanel NewPage()
    {
        var page = new StackPanel
        {
            Margin = new Thickness(22, 18, 22, 24),
            FlowDirection = FlowDirection.RightToLeft
        };
        Root.Children.Clear();
        Root.Children.Add(new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = page,
            FlowDirection = FlowDirection.RightToLeft
        });
        return page;
    }

    private static void AddHeader(StackPanel page, string heading, string subtitle)
    {
        page.Children.Add(Text(heading, 28, WhiteBrush, true));
        page.Children.Add(Text(subtitle, 14, MutedBrush));
        page.Children.Add(new Border { Height = 1, Background = new SolidColorBrush(Color.FromRgb(55, 55, 55)), Margin = new Thickness(0, 10, 0, 10) });
    }

    private void ShowSetup()
    {
        var page = NewPage();
        page.Children.Add(Text("כספת", 34, WhiteBrush, true));
        page.Children.Add(Text("הגדרה ראשונית · שני קודים לפתיחת אותה כספת", 15));
        var pin = SecretField("קוד בן 4 עד 8 ספרות");
        var confirmPin = SecretField("אימות הקוד");
        string? firstPattern = null;
        string? confirmedPattern = null;
        var status = Text("טרם הוגדר קוד קווים", 14, AccentBrush);
        var pad = new PatternPad();
        pad.PatternCompleted += pattern =>
        {
            if (pattern.Length < 4)
            {
                status.Text = "יש לצייר לפחות 4 נקודות";
                status.Foreground = AccentBrush;
                return;
            }
            if (firstPattern == null)
            {
                firstPattern = pattern;
                status.Text = "כעת צייר שוב את אותו הקוד לאימות";
            }
            else if (firstPattern == pattern)
            {
                confirmedPattern = pattern;
                status.Text = "קוד הקווים אומת בהצלחה";
                status.Foreground = new SolidColorBrush(Color.FromRgb(118, 190, 132));
            }
            else
            {
                firstPattern = pattern;
                confirmedPattern = null;
                status.Text = "הקווים לא תואמים. הקוד הראשון עודכן; צייר שוב לאימות";
                status.Foreground = AccentBrush;
            }
        };
        page.Children.Add(Card(
            Text("שלב 1 · קוד מספרי", 19, WhiteBrush, true),
            Text("בחר קוד בן 4 עד 8 ספרות והקלד אותו פעמיים."),
            pin,
            confirmPin,
            Text("שלב 2 · קוד קווים", 19, WhiteBrush, true),
            Text("צייר לפחות 4 נקודות ברשת. לאחר מכן צייר שוב לאימות."),
            new Border { Background = new SolidColorBrush(Color.FromRgb(9, 11, 11)), CornerRadius = new CornerRadius(12), Padding = new Thickness(8), Child = pad },
            status,
            Text("חשוב: אם תשכח את שני הקודים, אין דרך לשחזר את הקבצים.", 14, AccentBrush)
        ));
        page.Children.Add(Button("יצירת הכספת", () =>
        {
            string p = pin.Password;
            if (p.Length is < 4 or > 8 || p.Any(c => c < '0' || c > '9'))
            {
                MessageBox.Show("הקוד המספרי חייב להכיל 4 עד 8 ספרות.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            if (p != confirmPin.Password)
            {
                MessageBox.Show("הקודים המספריים אינם תואמים.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            if (confirmedPattern == null)
            {
                MessageBox.Show("יש להגדיר ולאמת קוד קווים של לפחות 4 נקודות.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            byte[] key = VaultCrypto.RandomBytes(32);
            try
            {
                store.SaveCredentials(Credentials.Create(key, p, confirmedPattern));
                store.SaveIndex([], key);
                store.ResetConsecutiveFailures();
                SetMasterKey(key);
                items = [];
                ShowHome();
            }
            catch (Exception ex)
            {
                CryptographicOperations.ZeroMemory(key);
                MessageBox.Show("יצירת הכספת נכשלה: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }, true));
    }

    private void ShowUnlock()
    {
        if (!store.HasCredentials())
        {
            ShowSetup();
            return;
        }

        var page = NewPage();
        page.HorizontalAlignment = HorizontalAlignment.Stretch;
        page.Children.Add(Text("כספת", 34, WhiteBrush, true));
        page.Children.Add(Text("המערכת מנטרת אותך... הזן קוד כדי להמשיך", 15));
        page.Children.Add(Text("◈", 54, AccentBrush, true));
        SettingsDocument settings = store.LoadSettings();
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (settings.LockoutUntil > now)
        {
            long seconds = Math.Max(1, (settings.LockoutUntil - now) / 1000);
            page.Children.Add(Text("הכספת נעולה זמנית. נסה שוב בעוד " + seconds + " שניות.", 16, AccentBrush));
        }

        var pin = SecretField("4 עד 8 ספרות");
        var pinCard = Card(
            Text("קוד מספרי", 20, WhiteBrush, true),
            pin,
            Button("פתיחה עם קוד מספרי", () => AttemptUnlock(pin.Password, "pin"), true)
        );
        page.Children.Add(pinCard);

        var patternCardStack = new StackPanel { FlowDirection = FlowDirection.RightToLeft };
        patternCardStack.Children.Add(Text("קוד קווים", 20, WhiteBrush, true));
        patternCardStack.Children.Add(Text("צייר את הקוד האישי שלך ברשת"));
        var patternPad = new PatternPad();
        patternPad.PatternCompleted += pattern => AttemptUnlock(pattern, "pattern");
        patternCardStack.Children.Add(new Border
        {
            Background = new SolidColorBrush(Color.FromRgb(9, 11, 11)),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(8),
            Child = patternPad
        });
        page.Children.Add(new Border
        {
            Background = PanelBrush,
            BorderBrush = new SolidColorBrush(Color.FromArgb(102, 63, 37, 43)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(15),
            Padding = new Thickness(16),
            Margin = new Thickness(4, 7, 4, 7),
            Child = patternCardStack
        });
        page.Children.Add(Text("כל הנתונים נשמרים במחשב בלבד ומוצפנים.", 13));
    }

    private void AttemptUnlock(string code, string type)
    {
        SettingsDocument settings = store.LoadSettings();
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (settings.LockoutUntil > now)
        {
            MessageBox.Show("הכספת נעולה זמנית. יש להמתין לפני ניסיון נוסף.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
            ShowUnlock();
            return;
        }
        if (string.IsNullOrWhiteSpace(code))
        {
            MessageBox.Show("יש להזין קוד.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        try
        {
            CredentialSet? credentials = store.LoadCredentials();
            byte[]? key = credentials == null ? null : Credentials.Unlock(credentials, code, type);
            if (key == null)
            {
                FailureRecord failure = store.RecordFailure();
                SettingsDocument afterFailure = store.LoadSettings();
                if (afterFailure.ScareEnabled && failure.Consecutive % Math.Clamp(afterFailure.ScareThreshold, 1, 5) == 0)
                    ShowScareWindow();

                if (failure.Consecutive >= 5)
                {
                    int level = Math.Clamp(afterFailure.LockoutLevel, 0, 6);
                    long duration = 30_000L * (1L << level);
                    afterFailure.LockoutUntil = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + duration;
                    afterFailure.LockoutLevel = Math.Min(level + 1, 6);
                    afterFailure.ConsecutiveFailures = 0;
                    store.SaveSettings(afterFailure);
                    MessageBox.Show("יותר מדי ניסיונות. הכספת ננעלה לזמן קצר.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
                else
                {
                    MessageBox.Show("קוד שגוי.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
                ShowUnlock();
                return;
            }

            List<VaultItem> loaded;
            try { loaded = store.LoadIndex(key); }
            catch
            {
                CryptographicOperations.ZeroMemory(key);
                throw;
            }
            store.ResetConsecutiveFailures();
            SettingsDocument cleared = store.LoadSettings();
            cleared.LockoutUntil = 0;
            store.SaveSettings(cleared);
            SetMasterKey(key);
            items = loaded;
            ShowHome();
        }
        catch (Exception ex)
        {
            MessageBox.Show("לא ניתן לפתוח את הכספת: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
            ShowUnlock();
        }
    }

    private void ShowHome()
    {
        if (masterKey == null)
        {
            ShowUnlock();
            return;
        }

        var page = NewPage();
        page.Children.Add(Text("הכספת שלך", 29, WhiteBrush, true));
        page.Children.Add(Text(items.Count + " קבצים שמורים ומוצפנים במחשב", 14));
        var actions = new WrapPanel { FlowDirection = FlowDirection.RightToLeft };
        actions.Children.Add(Button("＋ הכנסת קבצים", ImportFiles, true));
        actions.Children.Add(Button("הגדרות", ShowSettings));
        actions.Children.Add(Button("גיבוי", CreateBackup));
        actions.Children.Add(Button("שחזור", RestoreBackup));
        actions.Children.Add(Button("נעילה", LockVault));
        page.Children.Add(actions);
        page.Children.Add(Text("הקבצים שלי", 21, WhiteBrush, true));
        var search = TextField(searchText, "חיפוש לפי שם קובץ");
        search.TextAlignment = TextAlignment.Right;
        page.Children.Add(search);

        var sortRow = new WrapPanel { FlowDirection = FlowDirection.RightToLeft };
        sortRow.Children.Add(Button("תאריך", () => { sortMode = "date"; RenderFiles(); }));
        sortRow.Children.Add(Button("שם", () => { sortMode = "name"; RenderFiles(); }));
        sortRow.Children.Add(Button("גודל", () => { sortMode = "size"; RenderFiles(); }));
        page.Children.Add(sortRow);

        var list = new StackPanel { FlowDirection = FlowDirection.RightToLeft };
        page.Children.Add(list);
        void Render()
        {
            searchText = search.Text;
            list.Children.Clear();
            IEnumerable<VaultItem> filtered = items.Where(x => x.Name.Contains(searchText.Trim(), StringComparison.OrdinalIgnoreCase));
            filtered = sortMode switch
            {
                "name" => filtered.OrderBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase),
                "size" => filtered.OrderByDescending(x => x.Size),
                _ => filtered.OrderByDescending(x => x.Created)
            };
            var result = filtered.ToList();
            if (result.Count == 0)
            {
                list.Children.Add(Card(Text(items.Count == 0
                    ? "הכספת עדיין ריקה. לחץ על 'הכנסת קבצים' כדי להתחיל."
                    : "לא נמצאו קבצים מתאימים.", 15)));
                return;
            }
            foreach (VaultItem item in result)
            {
                var buttons = new WrapPanel { FlowDirection = FlowDirection.RightToLeft };
                buttons.Children.Add(Button("פתיחה", () => PreviewItem(item), true));
                buttons.Children.Add(Button("הוצאה", () => ExportItem(item)));
                buttons.Children.Add(Button("מחיקה", () => DeleteItem(item)));
                list.Children.Add(Card(
                    Text(item.Name, 17, WhiteBrush, true),
                    Text(item.Mime + "  ·  " + FormatSize(item.Size), 12),
                    buttons
                ));
            }
        }
        search.TextChanged += (_, _) => Render();
        void RenderFiles() => Render();
        Render();
    }

    private async void ImportFiles()
    {
        var dialog = new OpenFileDialog
        {
            Title = "בחירת קבצים להכנסה לכספת",
            Multiselect = true,
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != true || masterKey == null) return;
        byte[] key = masterKey.ToArray();
        try
        {
            foreach (string path in dialog.FileNames)
            {
                try
                {
                    VaultItem item = await Task.Run(() => store.ImportFile(path, key));
                    List<VaultItem> updated = items.Append(item).ToList();
                    try { await Task.Run(() => store.SaveIndex(updated, key)); }
                    catch
                    {
                        store.Delete(item);
                        throw;
                    }
                    items = updated;
                    var deleteOriginal = MessageBox.Show(
                        "הקובץ '" + Path.GetFileName(path) + "' הועתק לכספת. האם למחוק גם את המקור מהמחשב?",
                        "מחיקת הקובץ המקורי",
                        MessageBoxButton.YesNo,
                        MessageBoxImage.Question);
                    if (deleteOriginal == MessageBoxResult.Yes)
                    {
                        try { File.Delete(path); }
                        catch (Exception ex) { MessageBox.Show("לא ניתן היה למחוק את המקור: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Warning); }
                    }
                }
                catch (Exception ex)
                {
                    MessageBox.Show("ייבוא הקובץ נכשל: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }
        finally { CryptographicOperations.ZeroMemory(key); }
        ShowHome();
    }

    private async void ExportItem(VaultItem item)
    {
        var dialog = new SaveFileDialog
        {
            Title = "הוצאת קובץ מהכספת",
            FileName = item.Name,
            AddExtension = false
        };
        if (dialog.ShowDialog(this) != true || masterKey == null) return;
        byte[] key = masterKey.ToArray();
        try
        {
            await Task.Run(() => store.ExportFile(item, dialog.FileName, key));
            MessageBox.Show("הקובץ יוצא בהצלחה.", "כספת", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show("הוצאת הקובץ נכשלה: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    private async void PreviewItem(VaultItem item)
    {
        if (masterKey == null) return;
        byte[] key = masterKey.ToArray();
        string? temp = null;
        try
        {
            temp = await Task.Run(() => store.DecryptToTemp(item, key));
            if (item.Mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            {
                var bitmap = new BitmapImage();
                bitmap.BeginInit();
                bitmap.CacheOption = BitmapCacheOption.OnLoad;
                bitmap.UriSource = new Uri(temp);
                bitmap.EndInit();
                bitmap.Freeze();
                var image = new Image { Source = bitmap, Stretch = Stretch.Uniform, MaxWidth = 900, MaxHeight = 700 };
                ShowPreviewWindow(item.Name, image, 940, 760);
            }
            else if (item.Mime.StartsWith("text/", StringComparison.OrdinalIgnoreCase) ||
                     item.Mime.Contains("json", StringComparison.OrdinalIgnoreCase) ||
                     item.Mime.Contains("xml", StringComparison.OrdinalIgnoreCase))
            {
                string text = await File.ReadAllTextAsync(temp, Encoding.UTF8);
                if (text.Length > 400_000) text = text[..400_000] + "\n\n[התצוגה קוצרה]";
                var box = new TextBox
                {
                    Text = text,
                    IsReadOnly = true,
                    AcceptsReturn = true,
                    TextWrapping = TextWrapping.Wrap,
                    VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                    HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                    FlowDirection = FlowDirection.LeftToRight,
                    MinWidth = 700,
                    MinHeight = 500
                };
                ShowPreviewWindow(item.Name, box, 900, 700);
            }
            else
            {
                MessageBox.Show("סוג הקובץ הזה ניתן להוצאה בלבד.", "כספת", MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show("לא ניתן לפתוח את הקובץ: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            if (temp != null) try { File.Delete(temp); } catch { }
            CryptographicOperations.ZeroMemory(key);
        }
    }

    private void ShowPreviewWindow(string title, UIElement content, double width, double height)
    {
        var window = new Window
        {
            Title = title,
            Owner = this,
            Width = width,
            Height = height,
            MinWidth = 500,
            MinHeight = 350,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = Bg,
            Foreground = WhiteBrush,
            Content = new Border { Background = PanelBrush, Padding = new Thickness(12), Child = content }
        };
        window.ShowDialog();
    }

    private void DeleteItem(VaultItem item)
    {
        if (MessageBox.Show("הקובץ יימחק מהכספת ולא יהיה אפשר לשחזר אותו. להמשיך?",
            "מחיקת קובץ", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes || masterKey == null) return;
        byte[] key = masterKey.ToArray();
        try
        {
            store.Delete(item);
            List<VaultItem> updated = items.Where(x => x.Id != item.Id).ToList();
            store.SaveIndex(updated, key);
            items = updated;
            ShowHome();
        }
        catch (Exception ex)
        {
            MessageBox.Show("מחיקת הקובץ נכשלה: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    private void CreateBackup()
    {
        if (masterKey == null) return;
        var dialog = new SaveFileDialog
        {
            Title = "יצירת גיבוי מוצפן",
            FileName = "vault-backup.vault",
            DefaultExt = ".vault",
            AddExtension = true,
            Filter = "גיבוי כספת (*.vault)|*.vault"
        };
        if (dialog.ShowDialog(this) != true) return;
        byte[] key = masterKey.ToArray();
        try
        {
            store.WriteBackup(dialog.FileName, key);
            MessageBox.Show("הגיבוי המוצפן נוצר בהצלחה.", "כספת", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show("יצירת הגיבוי נכשלה: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally { CryptographicOperations.ZeroMemory(key); }
    }

    private void RestoreBackup()
    {
        var dialog = new OpenFileDialog
        {
            Title = "בחירת גיבוי כספת",
            Filter = "גיבוי כספת (*.vault)|*.vault|כל הקבצים (*.*)|*.*",
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != true) return;
        if (MessageBox.Show("השחזור יחליף את כל תוכן הכספת הנוכחית בתוכן הגיבוי. להמשיך?",
            "שחזור מגיבוי", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        string? code = PromptSecret("הזן את הקוד המספרי או את רצף נקודות הקווים ששימש ביצירת הגיבוי.");
        if (string.IsNullOrEmpty(code)) return;
        try
        {
            byte[] restored = store.RestoreBackup(dialog.FileName, code);
            List<VaultItem> restoredItems = store.LoadIndex(restored);
            SetMasterKey(restored);
            items = restoredItems;
            store.ResetConsecutiveFailures();
            MessageBox.Show("הגיבוי שוחזר בהצלחה.", "כספת", MessageBoxButton.OK, MessageBoxImage.Information);
            ShowHome();
        }
        catch (Exception ex)
        {
            MessageBox.Show("השחזור נכשל: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void ShowSettings()
    {
        var page = NewPage();
        AddHeader(page, "הגדרות", "ניהול אבטחה והתנהגות הכספת");
        page.Children.Add(Card(Text("אזהרה: אם תשכח את שני הקודים, אין דרך לשחזר את הקבצים.", 15, AccentBrush)));
        SettingsDocument settings = store.LoadSettings();
        var scare = new CheckBox
        {
            Content = "הפעלת מסך התרעה לאחר קוד שגוי",
            IsChecked = settings.ScareEnabled,
            FlowDirection = FlowDirection.RightToLeft
        };
        scare.Checked += (_, _) => { settings.ScareEnabled = true; store.SaveSettings(settings); };
        scare.Unchecked += (_, _) => { settings.ScareEnabled = false; store.SaveSettings(settings); };
        page.Children.Add(scare);

        page.Children.Add(Text("סף הפעלת ההתרעה (1–5 ניסיונות)", 15, WhiteBrush, true));
        var threshold = new ComboBox { Width = 220, HorizontalAlignment = HorizontalAlignment.Right };
        for (int i = 1; i <= 5; i++) threshold.Items.Add(i + " ניסיונות");
        threshold.SelectedIndex = Math.Clamp(settings.ScareThreshold, 1, 5) - 1;
        threshold.SelectionChanged += (_, _) =>
        {
            if (threshold.SelectedIndex >= 0)
            {
                settings.ScareThreshold = threshold.SelectedIndex + 1;
                store.SaveSettings(settings);
            }
        };
        page.Children.Add(threshold);
        page.Children.Add(Button("הדגמת מסך ההתרעה", ShowScareWindow, true));
        page.Children.Add(Button("שינוי קוד מספרי", ChangePin));
        page.Children.Add(Button("שינוי קוד קווים", ChangePattern));
        page.Children.Add(Button("יומן ניסיונות כושלים", ShowHistory));
        page.Children.Add(Button("חזרה לקבצים", ShowHome));
    }

    private void ChangePin()
    {
        var values = PromptTwoSecrets("שינוי קוד מספרי", "קוד חדש בן 4 עד 8 ספרות", "הקלד שוב את הקוד החדש");
        if (values == null) return;
        string pin = values.Value.First;
        if (pin.Length is < 4 or > 8 || pin.Any(c => c < '0' || c > '9') || pin != values.Value.Second)
        {
            MessageBox.Show("הקוד אינו תקין או שהאימות אינו תואם.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        if (masterKey == null) return;
        try
        {
            CredentialSet credentials = store.LoadCredentials() ?? throw new InvalidOperationException("פרטי הכניסה חסרים");
            store.SaveCredentials(Credentials.Replace(credentials, masterKey, pin, "pin"));
            MessageBox.Show("הקוד המספרי עודכן. קוד הקווים נשאר ללא שינוי.", "כספת", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show("עדכון הקוד נכשל: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void ChangePattern()
    {
        var panel = new StackPanel { Margin = new Thickness(20), FlowDirection = FlowDirection.RightToLeft };
        panel.Children.Add(Text("צייר קוד קווים חדש ולאחר מכן צייר אותו שוב לאימות.", 15));
        var status = Text("צייר לפחות 4 נקודות", 14, AccentBrush);
        string? first = null;
        string? accepted = null;
        var pad = new PatternPad();
        pad.PatternCompleted += pattern =>
        {
            if (pattern.Length < 4)
            {
                status.Text = "יש לצייר לפחות 4 נקודות";
                return;
            }
            if (first == null)
            {
                first = pattern;
                status.Text = "צייר שוב לאימות";
            }
            else if (first == pattern)
            {
                accepted = pattern;
                status.Text = "הקוד אומת בהצלחה";
                status.Foreground = new SolidColorBrush(Color.FromRgb(118, 190, 132));
            }
            else
            {
                first = pattern;
                accepted = null;
                status.Text = "לא תואם. הרצף החדש נקלט; צייר שוב";
            }
        };
        panel.Children.Add(new Border
        {
            Background = new SolidColorBrush(Color.FromRgb(9, 11, 11)),
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(8),
            Child = pad
        });
        panel.Children.Add(status);
        Window? patternWindow = null;
        var save = Button("שמירת קוד קווים חדש", () =>
        {
            if (accepted == null)
            {
                MessageBox.Show("יש להשלים את אימות הקווים.", "כספת", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }
            if (masterKey == null) return;
            try
            {
                CredentialSet credentials = store.LoadCredentials() ?? throw new InvalidOperationException("פרטי הכניסה חסרים");
                store.SaveCredentials(Credentials.Replace(credentials, masterKey, accepted, "pattern"));
                MessageBox.Show("קוד הקווים עודכן. הקוד המספרי נשאר ללא שינוי.", "כספת", MessageBoxButton.OK, MessageBoxImage.Information);
                patternWindow?.Close();
            }
            catch (Exception ex)
            {
                MessageBox.Show("עדכון הקוד נכשל: " + ex.Message, "כספת", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }, true);
        panel.Children.Add(save);
        patternWindow = new Window
        {
            Title = "שינוי קוד קווים",
            Owner = this,
            Width = 520,
            Height = 480,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = Bg,
            Content = panel
        };
        patternWindow!.ShowDialog();
    }

    private void ShowHistory()
    {
        var page = NewPage();
        AddHeader(page, "יומן ניסיונות", "היומן נשמר במחשב בלבד.");
        SettingsDocument settings = store.LoadSettings();
        var events = settings.AttemptHistory.OrderByDescending(x => x.Time).ToList();
        if (events.Count == 0) page.Children.Add(Card(Text("אין ניסיונות כושלים שמורים.", 15)));
        foreach (FailedAttempt entry in events)
        {
            string date = DateTimeOffset.FromUnixTimeMilliseconds(entry.Time).ToLocalTime().ToString("dd/MM/yyyy · HH:mm:ss", CultureInfo.GetCultureInfo("he-IL"));
            page.Children.Add(Card(
                Text("ניסיון כושל", 17, WhiteBrush, true),
                Text(date),
                Text("רצף ניסיונות: " + entry.Count, 13)
            ));
        }
        page.Children.Add(Button("מחיקת יומן הניסיונות", () =>
        {
            if (MessageBox.Show("כל רשומות הניסיונות יימחקו לצמיתות. להמשיך?",
                "מחיקת יומן", MessageBoxButton.YesNo, MessageBoxImage.Warning) == MessageBoxResult.Yes)
            {
                store.ClearAttemptHistory();
                ShowHistory();
            }
        }));
        page.Children.Add(Button("חזרה להגדרות", ShowSettings));
    }

    private void ShowScareWindow()
    {
        Window? scareWindow = null;
        var panel = new StackPanel
        {
            Margin = new Thickness(24),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            FlowDirection = FlowDirection.RightToLeft
        };
        panel.Children.Add(Text("◈", 46, AccentBrush, true));
        panel.Children.Add(Text("מרכז אבטחה מקומי", 25, WhiteBrush, true));
        panel.Children.Add(Text("הדמיה בלבד · לא נשלח דיווח ולא מתבצעת תקשורת", 13, AccentBrush));
        panel.Children.Add(Text("נרשם ניסיון גישה לא מורשה. המידע נשמר מקומית בלבד.", 16, WhiteBrush));
        panel.Children.Add(Text("אין צילום מצלמה בגרסת Windows. לא נאסף מידע ביומטרי.", 13));
        panel.Children.Add(Button("סגירה", () => scareWindow?.Close(), true));
        scareWindow = new Window
        {
            Title = "מרכז אבטחה מקומי",
            Owner = this,
            Width = 540,
            Height = 360,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = Bg,
            Foreground = WhiteBrush,
            Content = panel
        };
        scareWindow!.ShowDialog();
    }

    private string? PromptSecret(string message)
    {
        var panel = new StackPanel { Margin = new Thickness(18), FlowDirection = FlowDirection.RightToLeft };
        panel.Children.Add(Text(message, 14, WhiteBrush));
        var field = SecretField("קוד גיבוי");
        panel.Children.Add(field);
        string? result = null;
        var window = new Window
        {
            Title = "פתיחת הגיבוי",
            Owner = this,
            Width = 430,
            Height = 220,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = Bg,
            Content = panel
        };
        panel.Children.Add(Button("אישור", () => { result = field.Password; window.DialogResult = true; }, true));
        panel.Children.Add(Button("ביטול", () => window.DialogResult = false));
        return window.ShowDialog() == true ? result : null;
    }

    private (string First, string Second)? PromptTwoSecrets(string title, string firstLabel, string secondLabel)
    {
        var panel = new StackPanel { Margin = new Thickness(18), FlowDirection = FlowDirection.RightToLeft };
        panel.Children.Add(Text(firstLabel, 14, WhiteBrush));
        var first = SecretField();
        panel.Children.Add(first);
        panel.Children.Add(Text(secondLabel, 14, WhiteBrush));
        var second = SecretField();
        panel.Children.Add(second);
        (string First, string Second)? result = null;
        var window = new Window
        {
            Title = title,
            Owner = this,
            Width = 430,
            Height = 320,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Background = Bg,
            Content = panel
        };
        panel.Children.Add(Button("המשך", () =>
        {
            result = (first.Password, second.Password);
            window.DialogResult = true;
        }, true));
        panel.Children.Add(Button("ביטול", () => window.DialogResult = false));
        return window.ShowDialog() == true ? result : null;
    }

    private void SetMasterKey(byte[] key)
    {
        if (masterKey != null && !ReferenceEquals(masterKey, key))
            CryptographicOperations.ZeroMemory(masterKey);
        masterKey = key;
    }

    private void LockVault()
    {
        ClearSession();
        ShowUnlock();
    }

    private void ClearSession()
    {
        if (masterKey != null) CryptographicOperations.ZeroMemory(masterKey);
        masterKey = null;
        items = [];
        string temp = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "כספת", "vault", ".tmp");
        if (Directory.Exists(temp))
        {
            foreach (string file in Directory.GetFiles(temp, "preview-*"))
                try { File.Delete(file); } catch { }
        }
    }

    private void HandleWindowStateChanged(object? sender, EventArgs e)
    {
        if (WindowState == WindowState.Minimized && masterKey != null)
        {
            ClearSession();
            relockOnRestore = true;
        }
        else if (WindowState != WindowState.Minimized && relockOnRestore)
        {
            relockOnRestore = false;
            ShowUnlock();
        }
    }

    private void HandleClosed(object? sender, EventArgs e) => ClearSession();

    private static string FormatSize(long bytes)
    {
        if (bytes < 1024) return bytes + " בייט";
        if (bytes < 1024L * 1024) return (bytes / 1024.0).ToString("0.0", CultureInfo.InvariantCulture) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024.0 * 1024)).ToString("0.0", CultureInfo.InvariantCulture) + " MB";
        return (bytes / (1024.0 * 1024 * 1024)).ToString("0.00", CultureInfo.InvariantCulture) + " GB";
    }
}