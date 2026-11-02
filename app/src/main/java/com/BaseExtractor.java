package com;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout.Alignment;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.ext.EbookMeta;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.wrapper.MagicHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public abstract class BaseExtractor {

    public abstract EbookMeta getBookMetaInformation(String path);

    public abstract byte[] getBookCover(String path);

    public abstract Map<String, String> getFooterNotes(String path);


    public abstract String getBookOverview(String path);

    /** Plain jackets match the bookshelf; real embedded covers never enter this method. */
    public static Bitmap getBookCoverWithTitleBitmap(String firstLine, String bookTitle) {
        String author = TxtUtils.isEmpty(bookTitle) ? "" : (firstLine == null ? "" : firstLine);
        String title = TxtUtils.isEmpty(bookTitle) ? (firstLine == null ? "" : firstLine) : bookTitle;
        author=TxtUtils.ellipsize(author,128);title=TxtUtils.ellipsize(title.split(":",2)[0],256);
        int w = Math.max(120, Dips.dpToPx(Math.max(96,AppState.get().coverBigSize)));
        int h = Math.round(w * 1.4f);
        Bitmap bitmap=Bitmap.createBitmap(w,h,AppsConfig.CURRENT_BITMAP_ARGB);
        Canvas canvas=new Canvas(bitmap);
        int[] colors={Color.rgb(41,73,56),Color.rgb(78,93,88),Color.rgb(112,77,57),Color.rgb(68,79,105)};
        canvas.drawColor(colors[(title.hashCode() & Integer.MAX_VALUE)%colors.length]);
        int margin=Math.round(w*.10f), content=w-margin*2;
        TextPaint small=new TextPaint(TextPaint.ANTI_ALIAS_FLAG);small.setColor(Color.rgb(233,231,214));small.setTextSize(w*.075f);
        TextPaint display=new TextPaint(TextPaint.ANTI_ALIAS_FLAG);display.setColor(Color.rgb(249,244,223));
        display.setTypeface(Typeface.create("serif",Typeface.NORMAL));display.setTextSize(w*.14f);
        float longest=0;for(String word:title.split("\\s+"))longest=Math.max(longest,display.measureText(word));
        float safeTextWidth=content-Math.max(2,w*.02f);
        if(longest>safeTextWidth)display.setTextSize(Math.max(w*.09f,display.getTextSize()*safeTextWidth/longest));
        StaticLayout authorLayout=jacketText(author,small,content,2);
        canvas.save();canvas.translate(margin,Math.round(h*.10f));authorLayout.draw(canvas);
        canvas.translate(0,authorLayout.getHeight()+Math.round(w*.12f));
        jacketText(title,display,content,6).draw(canvas);canvas.restore();
        return bitmap;
    }

    private static StaticLayout jacketText(String text,TextPaint paint,int width,int lines) {
        return StaticLayout.Builder.obtain(text,0,text.length(),paint,width).setAlignment(Alignment.ALIGN_NORMAL)
                .setIncludePad(false).setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE).build();
    }

    public static byte[] getBookCoverWithTitle(String title) {
        try {

            Bitmap bitmap = getBookCoverWithTitleBitmap(title, "");

            byte[] byteArray = bitmapToByteArray(bitmap);

            return byteArray;
        } catch (OutOfMemoryError e) {
            LOG.e(e);
            return null;
        }

    }

    public static Bitmap getBookCoverWithTitle(String title, String author, boolean withLogo) {
        try {
            Bitmap bookCoverWithTitleBitmap = getBookCoverWithTitleBitmap(title, author);
            return bookCoverWithTitleBitmap;
        } catch (OutOfMemoryError e) {
            LOG.e(e);
            return null;
        }
    }

    public static Bitmap arrayToBitmap(byte[] array, int width) {
        try {
            if(array==null){
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(array, 0, array.length, options);
            int imageWidth = options.outWidth;
            options.inSampleSize = imageWidth / width;
            // options.inSampleSize = 1;
            options.inJustDecodeBounds = false;
            LOG.d("inSampleSize", options.inSampleSize);
            return BitmapFactory.decodeByteArray(array, 0, array.length, options);

        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    public static InputStream decodeImage(String path, int width) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int imageWidth = options.outWidth;
            options.inSampleSize = imageWidth / width;
            // options.inSampleSize = 1;
            options.inJustDecodeBounds = false;
            LOG.d("inSampleSize", options.inSampleSize);
            Bitmap decodeFile = BitmapFactory.decodeFile(path, options);

            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            decodeFile.compress(Bitmap.CompressFormat.PNG, 95, stream);

            return new ByteArrayInputStream(stream.toByteArray());

        } catch (Exception e) {
            return null;
        }
    }


    public static byte[] bitmapToByteArray(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 95, stream);
        byte[] byteArray = stream.toByteArray();
        bitmap.recycle();
        bitmap = null;
        try {
            stream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return byteArray;
    }

    public static final int BUFFER_SIZE = 16 * 1024;

    public static byte[] getEntryAsByte(InputStream zipInputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] bytesIn = new byte[BUFFER_SIZE];
        int read = 0;
        while ((read = zipInputStream.read(bytesIn)) != -1) {
            out.write(bytesIn, 0, read);
        }
        out.close();
        return out.toByteArray();
    }
}
