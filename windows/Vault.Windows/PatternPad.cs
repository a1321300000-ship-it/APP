using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace Vault.Windows;

internal sealed class PatternPad : FrameworkElement
{
    private readonly List<int> points = [];
    private bool tracking;
    public event Action<string>? PatternCompleted;

    public PatternPad()
    {
        Height = 240;
        MinHeight = 200;
        Focusable = true;
        SnapsToDevicePixels = true;
    }

    public void Clear()
    {
        points.Clear();
        tracking = false;
        if (IsMouseCaptured) ReleaseMouseCapture();
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc);
        double side = Math.Min(ActualHeight * 0.88, ActualWidth * 0.72);
        double margin = side * 0.13;
        double gap = (side - 2 * margin) / 2;
        double left = (ActualWidth - side) / 2 + margin;
        double top = (ActualHeight - side) / 2 + margin;
        Point Center(int index) => new(left + (index % 3) * gap, top + (index / 3) * gap);

        if (points.Count > 1)
        {
            var glow = new Pen(new SolidColorBrush(Color.FromArgb(45, 255, 49, 90)), Math.Max(10, gap * 0.16));
            var line = new Pen(new SolidColorBrush(Color.FromRgb(255, 74, 108)), Math.Max(3, gap * 0.055));
            for (int i = 1; i < points.Count; i++)
            {
                dc.DrawLine(glow, Center(points[i - 1]), Center(points[i]));
                dc.DrawLine(line, Center(points[i - 1]), Center(points[i]));
            }
        }

        double radius = Math.Max(8, gap * 0.17);
        for (int i = 0; i < 9; i++)
        {
            bool selected = points.Contains(i);
            Brush fill = selected
                ? new SolidColorBrush(Color.FromRgb(255, 49, 90))
                : new SolidColorBrush(Color.FromRgb(31, 48, 66));
            Pen outline = new(new SolidColorBrush(selected
                ? Color.FromRgb(255, 207, 217)
                : Color.FromRgb(101, 128, 151)), 2);
            if (selected)
                dc.DrawEllipse(new SolidColorBrush(Color.FromArgb(42, 255, 49, 90)), null, Center(i), radius * 1.9, radius * 1.9);
            dc.DrawEllipse(fill, outline, Center(i), radius, radius);
        }
    }

    protected override void OnMouseDown(MouseButtonEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.ChangedButton != MouseButton.Left) return;
        Clear();
        tracking = true;
        CaptureMouse();
        AddHit(e.GetPosition(this));
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (!tracking || e.LeftButton != MouseButtonState.Pressed) return;
        AddHit(e.GetPosition(this));
        e.Handled = true;
    }

    protected override void OnMouseUp(MouseButtonEventArgs e)
    {
        base.OnMouseUp(e);
        if (e.ChangedButton != MouseButton.Left || !tracking) return;
        tracking = false;
        if (IsMouseCaptured) ReleaseMouseCapture();
        string result = string.Concat(points.Select(x => x.ToString()));
        if (points.Count > 0) PatternCompleted?.Invoke(result);
        Clear();
        e.Handled = true;
    }

    private void AddHit(Point position)
    {
        double side = Math.Min(ActualHeight * 0.88, ActualWidth * 0.72);
        double margin = side * 0.13;
        double gap = (side - 2 * margin) / 2;
        double left = (ActualWidth - side) / 2 + margin;
        double top = (ActualHeight - side) / 2 + margin;
        double radius = gap * 0.60;
        int hit = -1;
        double best = radius;
        for (int i = 0; i < 9; i++)
        {
            double x = left + (i % 3) * gap;
            double y = top + (i / 3) * gap;
            double distance = Math.Sqrt(Math.Pow(position.X - x, 2) + Math.Pow(position.Y - y, 2));
            if (distance <= best)
            {
                hit = i;
                best = distance;
            }
        }
        if (hit < 0 || points.Contains(hit)) return;
        if (points.Count > 0)
        {
            int previous = points[^1];
            int rowDelta = hit / 3 - previous / 3;
            int colDelta = hit % 3 - previous % 3;
            int steps = Gcd(Math.Abs(rowDelta), Math.Abs(colDelta));
            if (steps > 1)
            {
                int rowStep = rowDelta / steps;
                int colStep = colDelta / steps;
                for (int i = 1; i < steps; i++)
                {
                    int middle = (previous / 3 + rowStep * i) * 3 + previous % 3 + colStep * i;
                    if (!points.Contains(middle)) points.Add(middle);
                }
            }
        }
        points.Add(hit);
        InvalidateVisual();
    }

    private static int Gcd(int a, int b)
    {
        while (b != 0) (a, b) = (b, a % b);
        return Math.Max(1, a);
    }
}