package com.example.myskills.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.Scroller;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;

import com.example.myskills.ui.util.LogUtil;

import java.text.DecimalFormat;

/**
 * FM刻度尺
 *
 * EthanLee
 */
public class FMMarkView extends View {
    private static final String TAG = "FMMarkView";
    // 画刻度线
    private Paint linePaint;
    // 画中间指示线
    private Paint guideLinePaint;
    // 画刻度值
    private Paint numberPaint;
    //当前频率默认值
    private float currentFrequency ;

    // 每刻度间隔
    private static int defaultMark = 6;
    // 指示器高度
    private static final int GUIDE_LINE_HEIGHT = 40;
    // 指示器宽度
    private static final int GUIDE_LINE_WIDTH = 4;
    // FM频段起始频率
    private static final float FM_BAND_START = 87f;
    // FM频段结束频率
    private static final float FM_BAND_END = 108f;
    // FM频段刻度间隔（MHz）
    private static final float FM_MARK_INTERVAL = 0.2f;
    // FM总刻度数
    private static final int FM_MARK_COUNT = (int) ((FM_BAND_END - FM_BAND_START) / FM_MARK_INTERVAL);
    // 总刻度数
    private static int markCount = FM_MARK_COUNT;
    // AM 频段起始频率
    private static final float AM_BAND_START = 530f;
    // AM频段结束频率
    private static final float AM_BAND_END = 1730f;
    // AM频段刻度间隔（kHz）
    private static final float AM_MARK_INTERVAL = 10f;
    // AM总刻度数
    private static final int AM_MARK_COUNT = (int) ((AM_BAND_END - AM_BAND_START) / AM_MARK_INTERVAL);
    // 短刻度线长度
    private static int shortLineLength = 16;
    // 长刻度线长度
    private static int longLineLength = 32;
    // 所有刻度总长度（+2，前后各留一个间隙）
    private static int contentTotalLength;
    // 频道数字颜色
    private int numLineColor = Color.parseColor("#FFFFFF"); // 白色
    // 指针颜色
    private int guideLineColor = Color.parseColor("#FFFF0000"); // 红色
    // FM刻度值保留一位小数
    private DecimalFormat fmNumFormat = new DecimalFormat("0");
    // AM刻度值格式化，精确到个位
    private DecimalFormat amNumFormat = new DecimalFormat("0");
    // 上一次滑动事件x值
    private float lastX;
    // 内容滑动的左边界
    private int leftBorder = 0;
    // 内容滑动的右边界
    private int rightBorder = 0;

    // 刻度线及刻度值颜色
    private int markLineColor = Color.parseColor("#FFFFFFFF");
    // 渐变色起始颜色（深灰色）
    private int gradientStartColor = Color.parseColor("#FF444444");
    // 刻度值字体大小
    private float numberTextSize = dipToPx(18);
    // 当前值字体大小
    private float currentNumberTextSize = dipToPx(25);

    /**
     * 设置当前频率数字字体大小
     * @param size 字体大小（px）
     */
    public void setCurrentNumberTextSize(float size) {
        this.currentNumberTextSize = size;
        invalidate();
    }

//    /**
//     * 设置当前频率数字颜色
//     * @param color 颜色值
//     */
//    public void setCurrentNumberColor(@ColorRes int color) {
//        this.numLineColor = getResources().getColor(color);
//        invalidate();
//    }


    private OnRadioChangeListener mOnRadioChangeListener;

    private VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private Scroller mScroller = new Scroller(getContext());

    public FMMarkView(Context context) {
        this(context, null);
    }

    public FMMarkView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FMMarkView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        linePaint = new Paint();
        linePaint.setColor(markLineColor);
        linePaint.setAntiAlias(true);
        linePaint.setDither(true);
        linePaint.setStrokeWidth(3);

        guideLinePaint = new Paint();
        guideLinePaint.setColor(guideLineColor);
        guideLinePaint.setAntiAlias(true);
        guideLinePaint.setDither(true);
        guideLinePaint.setStrokeWidth(3);

        numberPaint = new Paint();
        numberPaint.setColor(markLineColor);
        numberPaint.setAntiAlias(true);
        numberPaint.setDither(true);
        numberPaint.setTextSize(numberTextSize);
        numberPaint.setStrokeWidth(1);

        // 初始化时根据当前模式设置正确的总刻度数和内容长度
        updateContentLength();
        //默认值
        setRadioMode(RadioMode.FM);//设置默认模式
        setBandFrequency(98.5f);//设置默认频率
    }

//    /**
//     * 刻度及刻度值颜色
//     *
//     * @param color
//     */
//    public void setMarkLineColor(@ColorRes int color) {
//        this.markLineColor = getResources().getColor(color);
//        if (linePaint != null)
//            linePaint.setColor(markLineColor);
//        if (numberPaint != null)
//            numberPaint.setColor(markLineColor);
//    }

//    /**
//     * 指针及当前刻度值颜色
//     *
//     * @param color
//     */
//    public void setGuideLineColor(@ColorRes int color) {
//        this.guideLineColor = getResources().getColor(color);
//        if (guideLinePaint != null)
//            guideLinePaint.setColor(guideLineColor);
//    }

//    /**
//     * 设置刻度值字体颜色
//     *
//     * @param color 字体颜色
//     */
//    public void setNumberTextColor(@ColorRes int color) {
//        this.markLineColor = getResources().getColor(color);
//        if (numberPaint != null)
//            numberPaint.setColor(markLineColor);
//    }

//    /**
//     * 设置当前值字体颜色
//     *
//     * @param color 字体颜色
//     */
//    public void setCurrentNumberTextColor(@ColorRes int color) {
//        this.guideLineColor = getResources().getColor(color);
//        if (numberPaint != null)
//            numberPaint.setColor(guideLineColor);
//    }

    /**
     * 设置频道
     *
     * @param frequency 频率值
     */
    public void setBandFrequency(float frequency) {
        // 保护：等待布局完成
        if (getWidth() == 0) {
            post(() -> setBandFrequency(frequency));
            return;
        }

        // 频率值对齐到刻度间隔
        float interval = radioMode == RadioMode.FM ? FM_MARK_INTERVAL : AM_MARK_INTERVAL;
        float alignedFreq = Math.round(frequency / interval) * interval;
        if (radioMode == RadioMode.FM) {
            if (alignedFreq < FM_BAND_START || alignedFreq > FM_BAND_END) return;
        } else {
            if (alignedFreq < AM_BAND_START || alignedFreq > AM_BAND_END) return;
        }
        currentFrequency = alignedFreq;

        float destMarks;
        if (radioMode == RadioMode.FM) {
            destMarks = (alignedFreq - FM_BAND_START) / FM_MARK_INTERVAL;
        } else {
            destMarks = (alignedFreq - AM_BAND_START) / AM_MARK_INTERVAL;
        }

        int currentMarks = calculateCurrentMarks(null);
        scrollBy((int) ((destMarks - currentMarks) * dipToPx(defaultMark)), 0);

        // 对齐到最近刻度
        MotionEvent upEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
        calculateCurrentMarks(upEvent);
        upEvent.recycle();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 回收 VelocityTracker，防止内存泄漏
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /**
     * 获取当前FM频段
     *
     * @return 当前频段
     */
    public float getFM() {
        int currentMaks = calculateCurrentMarks(null);
        float currentFM = FM_BAND_START + currentMaks * FM_MARK_INTERVAL;
        LogUtil.d( "currentFM = " + currentFM);
        return currentFM;
    }

    /**
     * 获取当前AM频段
     *
     * @return 当前AM频段
     */
    public float getAM() {
        int currentMaks = calculateCurrentMarks(null);
        float currentAM = AM_BAND_START + currentMaks * AM_MARK_INTERVAL;
        LogUtil.d("currentAM = " + currentAM);
        return currentAM;
    }

    public interface OnRadioChangeListener {
        void onFMChang(float currentFM);

        void onAMChange(float currentAM);
    }

    public void setOnRadioChangeListener(OnRadioChangeListener onRadioChangeListener) {
        this.mOnRadioChangeListener = onRadioChangeListener;
    }

    /**
     * 回调当前FM
     */
    private void onFMChange() {
        if (this.mOnRadioChangeListener == null) return;
        this.mOnRadioChangeListener.onFMChang(getFM());
    }

    private void onAMChange() {
        if (this.mOnRadioChangeListener == null) return;
        this.mOnRadioChangeListener.onAMChange(getAM());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        if (heightMode == MeasureSpec.AT_MOST) {
            setMeasuredDimension(widthSize, (int) dipToPx(120));
            return;
        }
        setMeasuredDimension(widthSize, heightSize);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // 初始化内容滑动的左右边界
        getLeftBorder();
        getRightBorder();
    }

    @Override
    protected void onDraw(Canvas canvas) {

        // 画当前频率数字在view顶部
        //drawCurrentNumber(canvas);

        // 先画底部数字
        for (int i = 0; i <= markCount; i++) {
            if (i % 10 == 0) {
                drawNumbers(canvas, i);
            }
        }

        // 往上间隔20dp画刻度线
        float spacing = dipToPx(20);
        float centerY = getHeight() / 2 + spacing;

        // 计算可视范围内的刻度索引范围
        int scrollX = getScrollX();
        float viewLeft = scrollX;
        float viewRight = scrollX + getWidth();
        int firstVisibleMark = (int) (viewLeft / dipToPx(defaultMark)) - 1;
        int lastVisibleMark = (int) (viewRight / dipToPx(defaultMark)) + 1;

        // 确保索引在有效范围内
        firstVisibleMark = Math.max(0, firstVisibleMark);
        lastVisibleMark = Math.min(markCount, lastVisibleMark);

        // 画所有刻度线
        for (int i = 0; i <= markCount; i++) {
            float markStartX = dipToPx(defaultMark * (i + 1));
            float markEndX = dipToPx(defaultMark * (i + 1));
            float markStartY;
            float markEndY;

            if (i % 10 == 0) {
                // 长刻度线
                markStartY = centerY - dipToPx(longLineLength / 2);
                markEndY = centerY + dipToPx(longLineLength / 2);
            } else {
                // 短刻度线
                markStartY = centerY - dipToPx(shortLineLength / 2);
                markEndY = centerY + dipToPx(shortLineLength / 2);
            }

            // 设置刻度线颜色（处理边缘渐变）
            Paint currentPaint = linePaint;
            int color = markLineColor;

            // 左侧前10个刻度线：深灰色到白色渐变
            if (i >= firstVisibleMark && i < firstVisibleMark + 10) {
                float ratio = (i - firstVisibleMark) / 10.0f;
                color = blendColors(gradientStartColor, markLineColor, ratio);
                currentPaint = new Paint(linePaint);
                currentPaint.setColor(color);
            }
            // 右侧后10个刻度线：白色到深灰色渐变
            else if (i > lastVisibleMark - 10 && i <= lastVisibleMark) {
                float ratio = (lastVisibleMark - i) / 10.0f;
                color = blendColors(gradientStartColor, markLineColor, ratio);
                currentPaint = new Paint(linePaint);
                currentPaint.setColor(color);
            }

            canvas.drawLine(markStartX, markStartY, markEndX, markEndY, currentPaint);
        }

        // 画中心指示线（长条）
        float centerX = getWidth() / 2 + getScrollX();
        float indicatorTopY = centerY - dipToPx(GUIDE_LINE_HEIGHT / 2);
        float indicatorBottomY = centerY + dipToPx(GUIDE_LINE_HEIGHT / 2);
        float leftX = centerX - dipToPx(GUIDE_LINE_WIDTH / 2);
        float rightX = centerX + dipToPx(GUIDE_LINE_WIDTH / 2);
        canvas.drawRect(leftX, indicatorTopY, rightX, indicatorBottomY, guideLinePaint);


    }

    /**
     * 画当前刻度值
     *
     * @param canvas
     */
    private void drawCurrentNumber(Canvas canvas) {
        float centerX = getWidth() / 2 + getScrollX();

        int currentMaks = calculateCurrentMarks(null);
        String currentNumber ;
        String unit;
        //double contentNum;
        if (radioMode == RadioMode.FM) {
            //contentNum = FM_BAND_START + currentMaks * FM_MARK_INTERVAL;
            currentNumber = fmNumFormat.format(currentFrequency);
            unit = "MHz"; // 统一单位为小写
            onFMChange();
        } else {
            // 使用Math.round进行四舍五入，确保精确到个位
            //contentNum = AM_BAND_START + currentMaks * AM_MARK_INTERVAL;
            currentNumber = String.valueOf((int)Math.round(currentFrequency));
            unit = "kHz";
            onAMChange();
        }

        LogUtil.d("currentNumber = " + currentNumber + "--" + currentMaks );
        Rect textRect = getTextRect(numberPaint, currentNumber);
        float textWidth = textRect.width();
        float textHeight = textRect.height();

        // 设置为view顶部
        //float topPadding = dipToPx((int) currentNumberTextSize); // 距离顶部40dp

        int x = (int) (centerX - textWidth / 2);
        int currentBaseY = (int) textHeight*2;

        // 绘制当前频率数字
        numberPaint.setFakeBoldText(true);
        numberPaint.setTextSize(currentNumberTextSize);
        numberPaint.setColor(numLineColor);
        canvas.drawText(currentNumber, x, currentBaseY, numberPaint);

        // 绘制单位
        //numberPaint.setTextSize(dipToPx(12));
        //int unitX = (int) (centerX + textWidth * 3 / 4);
        //canvas.drawText(unit, unitX, currentBaseY, numberPaint);
    }

    /**
     * 画刻度值
     *
     * @param canvas
     * @param number 长刻度线的位置
     */
    private void drawNumbers(Canvas canvas, int number) {
        double frequency;
        String text;
        if (radioMode == RadioMode.FM) {
            // FM模式，刻度间隔为0.1MHz
            frequency = FM_BAND_START + number * FM_MARK_INTERVAL;
            // 当每10个刻度（即1MHz）时才绘制数字
            if (number % 10 != 0) return;
            text = fmNumFormat.format(frequency);
        } else {
            // AM模式，刻度间隔为10kHz
            frequency = AM_BAND_START + number * AM_MARK_INTERVAL;
            // AM模式下每10个刻度（即100kHz）才绘制数字
            if (number % 10 != 0) return;
            text = amNumFormat.format(frequency);
        }
        Rect textRect = getTextRect(numberPaint, text);
        int textWidth = textRect.width();
        int textHeight = textRect.height();
        numberPaint.setFakeBoldText(false);
        numberPaint.setTextSize(numberTextSize);
        numberPaint.setColor(markLineColor);
        canvas.drawText(text, (dipToPx(defaultMark * (number + 1)) - textWidth / 2),
                getHeight() - dipToPx(shortLineLength) + textHeight, numberPaint);
    }

    private Rect getTextRect(Paint textPaint, String text) {
        Rect rect = new Rect();
        textPaint.getTextBounds(text, 0, text.length(), rect);
        return rect;
    }

    private int getLeftBorder() {
        leftBorder = (int) (dipToPx(defaultMark) - getWidth() / 2);
        return leftBorder;
    }

    private int getRightBorder() {
        rightBorder = (int) (dipToPx((markCount + 1) * defaultMark) - getWidth() / 2);
        return rightBorder;
    }

    /**
     * 开始处理滑动事件
     *
     * @param event
     * @return
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mVelocityTracker.addMovement(event);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                break;
            case MotionEvent.ACTION_MOVE:
                int deltaX = (int) (lastX - event.getX());
                LogUtil.d( "deltaX = " + deltaX + "--getScrollX() = " + getScrollX());
                // 限制左边界
                if ((getScrollX() <= leftBorder) && (deltaX < 0)) {
                    scrollTo(leftBorder, 0);
                    break;
                }
                // 限制有边界
                if ((getScrollX() >= rightBorder) && (deltaX > 0)) {
                    scrollTo(rightBorder, 0);
                    break;
                }
                // 界内滑动
                scrollBy(deltaX, 0);
                lastX = event.getX();
                break;
            case MotionEvent.ACTION_UP:
                // 计算滑动速度
                computeVelocity();
                break;
        }
        calculateCurrentMarks(event);
        return true;
    }

    /**
     * 计算滑动速度
     */
    private void computeVelocity() {
        if (mVelocityTracker == null) return;
        mVelocityTracker.computeCurrentVelocity(1000);
        float velocityX = mVelocityTracker.getXVelocity();
        LogUtil.d("velocityX = " + velocityX);
        // 初始化 Scroller
        setFling((int) velocityX);
    }

    private void setFling(int vx) {
        if (mScroller == null) return;
        fling(mScroller.getCurrX(), 0, -vx, 0, leftBorder, rightBorder, 0, 0);
    }

    /**
     * @param startX    起始 X
     * @param startY    起始 Y
     * @param velocityX X 方向速度
     * @param velocityY Y 方向速度
     * @param minX      左边界
     * @param maxX      右边界
     * @param minY      上边界
     * @param maxY      下边界
     */
    private void fling(int startX, int startY, int velocityX, int velocityY,
                       int minX, int maxX, int minY, int maxY) {
        if (mScroller == null) return;
        mScroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY);
    }

    /**
     * Scroll 回调
     */
    @Override
    public void computeScroll() {
        LogUtil.d("computeScroll = " + (mScroller != null && !mScroller.isFinished()));
        if (mScroller == null) return;
        if (mScroller.computeScrollOffset()) {
            scrollTo(mScroller.getCurrX(), 0);
        } else {
            MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0, 0, 0);
            try {
                calculateCurrentMarks(event);
            } finally {
                event.recycle();
            }
        }
    }

    /**
     * 计算当前刻度
     *
     * @param event
     * @return
     */
    // 收音机模式
    private RadioMode radioMode = RadioMode.FM;

    public enum RadioMode {
        FM, AM
    }

    private void updateContentLength() {
        // 根据当前radioMode动态计算总刻度数和内容长度
        markCount = (radioMode == RadioMode.FM) ? FM_MARK_COUNT : AM_MARK_COUNT;
        contentTotalLength = (markCount + 2) * defaultMark;
    }

    public void setRadioMode(RadioMode mode) {
        this.radioMode = mode;
        // 切换模式时，更新总刻度数和内容长度
        updateContentLength();
        invalidate();
    }

    public RadioMode getRadioMode() {
        return radioMode;
    }

    private int calculateCurrentMarks(MotionEvent event) {
        float guideLineX = getWidth() / 2.0f;
        float markSpacingPx = dipToPx(defaultMark);
        float contentX = getScrollX() + guideLineX - markSpacingPx;
        int marks = (int) (contentX / markSpacingPx);
        if (contentX % markSpacingPx > dipToPx((int) (defaultMark / 2.0f))) {
            marks += 1;
            if ((event != null) && (event.getAction() == MotionEvent.ACTION_UP)) {
                scrollBy((int) (markSpacingPx - (contentX % markSpacingPx)), 0); //五入，ACTION_UP 时跳到刻度线
            }
        } else {
            if ((event != null) && (event.getAction() == MotionEvent.ACTION_UP)) {
                scrollBy((int) (-contentX % markSpacingPx), 0); // 四舍，ACTION_UP 时跳到刻度线
            }
        }
        LogUtil.d("marks = " + marks);
        return marks;
    }

    private float dipToPx(int dip) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, getResources().getDisplayMetrics());
    }

    /**
     * 混合两种颜色，根据比例从startColor渐变到endColor
     * @param startColor 起始颜色（灰色）
     * @param endColor 结束颜色（白色）
     * @param ratio 比例，0.0为startColor，1.0为endColor
     * @return 混合后的颜色
     */
    private int blendColors(int startColor, int endColor, float ratio) {
        // 提取ARGB分量
        int startA = Color.alpha(startColor);
        int startR = Color.red(startColor);
        int startG = Color.green(startColor);
        int startB = Color.blue(startColor);

        int endA = Color.alpha(endColor);
        int endR = Color.red(endColor);
        int endG = Color.green(endColor);
        int endB = Color.blue(endColor);

        // 计算混合后的颜色分量
        int a = (int) (startA + (endA - startA) * ratio);
        int r = (int) (startR + (endR - startR) * ratio);
        int g = (int) (startG + (endG - startG) * ratio);
        int b = (int) (startB + (endB - startB) * ratio);

        return Color.argb(a, r, g, b);
    }

    /**
     * 清理资源，建议在 Activity/Fragment 销毁时调用
     */
    public void release() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        if (mScroller != null) {
            mScroller.forceFinished(true);
        }
        mOnRadioChangeListener = null;
    }
}

