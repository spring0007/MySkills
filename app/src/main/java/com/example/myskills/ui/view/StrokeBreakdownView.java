package com.example.myskills.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.example.myskills.R;

/**
 * 笔画拆解：某字有 N 笔就横排 N 个田字格，第 k 格画出第 1..k 笔（累积），最后一格即整字。
 *
 * 数据坐标系是 hanzi-writer 的 1024x1024、y 轴向上，等价 viewBox "0 -124 1024 1024"，
 * 在 {@link #setStrokes} 里用同一个 Matrix 一次性变换到格子内坐标。
 *
 * 宽度按内容算、忽略 measureSpec —— 这是外层 HorizontalScrollView 能滚起来的前提。
 * 样式沿用本工程约定：不建 attrs.xml，颜色硬编码在类里。
 */
public class StrokeBreakdownView extends View {

    /** 数据坐标系的宽度，也是字面上下边界的间距（y 从 900 到 -124） */
    private static final float DATA_SIZE = 1024f;
    /** 数据坐标系的下边界（对应格子顶部） */
    private static final float DATA_BOTTOM = 900f;

    /** 笔画颜色，与 writer.html 里的 strokeColor 保持一致 */
    private static final int COLOR_STROKE = 0xFF333333;
    /** 田字格外框 */
    private static final int COLOR_GRID_BORDER = 0xFFE8AFAF;
    /** 田字格横竖虚线 */
    private static final int COLOR_GRID_LINE = 0xFFF2CDCD;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridDashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int cellSize;
    private final int cellGap;
    private final int gridPadding;

    /** 逐笔轮廓，已变换到「格子左上角为原点」的坐标 */
    private Path[] strokes = new Path[0];

    public StrokeBreakdownView(Context context) {
        this(context, null);
    }

    public StrokeBreakdownView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StrokeBreakdownView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        cellSize = getResources().getDimensionPixelSize(R.dimen.stroke_cell_size);
        cellGap = getResources().getDimensionPixelSize(R.dimen.stroke_cell_gap);
        gridPadding = getResources().getDimensionPixelSize(R.dimen.stroke_grid_padding);

        // 笔画是闭合的填充轮廓，直接 fill 就是正确字形；
        // 内部有洞的字（如「口」「日」）靠 WINDING 的非零环绕规则挖空
        strokePaint.setStyle(Paint.Style.FILL);
        strokePaint.setColor(COLOR_STROKE);

        float borderWidth = dipToPx(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(borderWidth);
        gridPaint.setColor(COLOR_GRID_BORDER);

        float dash = dipToPx(3f);
        gridDashPaint.setStyle(Paint.Style.STROKE);
        gridDashPaint.setStrokeWidth(borderWidth);
        gridDashPaint.setColor(COLOR_GRID_LINE);
        gridDashPaint.setPathEffect(new DashPathEffect(new float[]{dash, dash}, 0));
    }

    /**
     * 设置逐笔 SVG path。null 或空数组表示清空（整行隐藏时调用）。
     */
    public void setStrokes(@Nullable String[] svgPaths) {
        if (svgPaths == null || svgPaths.length == 0) {
            strokes = new Path[0];
            requestLayout();
            invalidate();
            return;
        }

        // 格子边长固定，所以变换矩阵也是常量，解析后直接变换一次，draw 时不再算
        float side = cellSize - 2f * gridPadding;
        float k = side / DATA_SIZE;
        Matrix matrix = new Matrix();
        matrix.setScale(k, -k);
        matrix.postTranslate(gridPadding, gridPadding + DATA_BOTTOM * k);

        Path[] parsed = new Path[svgPaths.length];
        for (int i = 0; i < svgPaths.length; i++) {
            Path path = new Path();
            path.setFillType(Path.FillType.WINDING);
            SvgPathParser.parse(svgPaths[i], path);
            path.transform(matrix);
            parsed[i] = path;
        }
        strokes = parsed;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = strokes.length;
        if (count == 0) {
            setMeasuredDimension(0, 0);
            return;
        }
        // 宽度无条件按内容算：写死 match_parent 会让外层 HorizontalScrollView 永远滚不动
        int width = count * cellSize + (count - 1) * cellGap
                + getPaddingLeft() + getPaddingRight();
        int height = cellSize + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = strokes.length;
        if (count == 0) return;

        float originX = getPaddingLeft();
        float originY = getPaddingTop();
        for (int i = 0; i < count; i++) {
            canvas.save();
            canvas.translate(originX + i * (cellSize + cellGap), originY);
            drawGrid(canvas);
            // 累积：第 i 格画出第 1..i+1 笔
            for (int j = 0; j <= i; j++) {
                canvas.drawPath(strokes[j], strokePaint);
            }
            canvas.restore();
        }
    }

    /** 画一个田字格：外框实线 + 横竖两条虚线中线 */
    private void drawGrid(Canvas canvas) {
        float size = cellSize;
        float half = size / 2f;
        // 外框描边有宽度，内缩半个线宽，否则会被 View 边界裁掉一半
        float inset = gridPaint.getStrokeWidth() / 2f;
        canvas.drawRect(inset, inset, size - inset, size - inset, gridPaint);
        canvas.drawLine(0, half, size, half, gridDashPaint);
        canvas.drawLine(half, 0, half, size, gridDashPaint);
    }

    private float dipToPx(float dip) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip,
                getResources().getDisplayMetrics());
    }
}
