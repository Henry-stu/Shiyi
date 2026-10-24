package com.hh.recall.importing;

import android.content.Context;
import android.graphics.PointF;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strict Markdown and native PDF extraction. Adapted for Recall from the independent import lab. */
public final class TextImporter {
    public static final int MAX_BYTES=8*1024*1024, MAX_PDF_BYTES=32*1024*1024, MAX_PAGES=100;
    public interface Progress { void page(int completed, int total); }
    public static class Failure extends IOException {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
    }
    public static final class Result {
        public String body="", encoding="";
        public final List<String> pages=new ArrayList<>(), warnings=new ArrayList<>();
        public final List<Integer> blankPages=new ArrayList<>();
        public JSONObject json() throws Exception {
            return new JSONObject().put("body",body).put("encoding",encoding)
                .put("pages",new JSONArray(pages)).put("warnings",new JSONArray(warnings))
                .put("blank_pages",new JSONArray(blankPages));
        }
    }
    private static void check(AtomicBoolean cancel) throws Failure {
        if(cancel.get() || Thread.currentThread().isInterrupted()) throw new Failure("CANCELLED");
    }
    public static byte[] boundedRead(InputStream in, AtomicBoolean cancel) throws IOException {
        return boundedRead(in,cancel,MAX_BYTES);
    }
    static byte[] boundedRead(InputStream in, AtomicBoolean cancel,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[16384]; int n;
        while((n=in.read(buf))!=-1) {
            check(cancel);
            if(out.size()+n>limit) throw new Failure("SIZE_LIMIT");
            out.write(buf,0,n);
        }
        check(cancel); return out.toByteArray();
    }
    public static Result read(Context context, InputStream input, String family, String explicitEncoding,
            AtomicBoolean cancel, Progress progress) throws IOException {
        byte[] data=boundedRead(input,cancel,"pdf".equals(family)?MAX_PDF_BYTES:MAX_BYTES);
        if("markdown".equals(family)) return markdown(data,explicitEncoding,cancel);
        if(!"pdf".equals(family)) throw new Failure("UNSUPPORTED_TYPE");
        PDFBoxResourceLoader.init(context.getApplicationContext());
        return pdf(data,cancel,progress);
    }
    private static Result markdown(byte[] data,String explicit,AtomicBoolean cancel) throws IOException {
        String encoding="UTF-8"; int offset=0;
        if(explicit!=null && !explicit.isEmpty()) encoding=explicit;
        else if(data.length>=3 && (data[0]&255)==239 && (data[1]&255)==187 && (data[2]&255)==191) offset=3;
        else if(data.length>=2 && (data[0]&255)==255 && (data[1]&255)==254) {encoding="UTF-16LE";offset=2;}
        else if(data.length>=2 && (data[0]&255)==254 && (data[1]&255)==255) {encoding="UTF-16BE";offset=2;}
        String text;
        try {
            text=Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,offset,data.length-offset)).toString();
        } catch(CharacterCodingException | IllegalArgumentException e) {throw new Failure("ENCODING_REQUIRED");}
        boolean nonblank=false;
        for(int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if(ch<32 && ch!='\n' && ch!='\r' && ch!='\t') throw new Failure("BINARY_OR_CONTROL_DATA");
            if(!Character.isWhitespace(ch) && ch!='\ufeff') nonblank=true;
        }
        if(!nonblank) throw new Failure("EMPTY_DOCUMENT");
        check(cancel);
        Result r=new Result();r.encoding=encoding;r.body=text.replace("\r\n","\n").replace('\r','\n');return r;
    }
    private static Result pdf(byte[] data,AtomicBoolean cancel,Progress progress) throws IOException {
        if(data.length<5 || !new String(data,0,Math.min(1024,data.length),StandardCharsets.ISO_8859_1).contains("%PDF-")) throw new Failure("INVALID_PDF");
        Result r=new Result();
        try(PDDocument document=PDDocument.load(data)) {
            if(document.isEncrypted()) throw new Failure("PASSWORD_REQUIRED");
            int count=document.getNumberOfPages();
            if(count>MAX_PAGES) throw new Failure("PAGE_LIMIT");
            if(count==0) throw new Failure("EMPTY_DOCUMENT");
            boolean missing=false, graphicsOnly=false;
            for(int i=0;i<count;i++) {
                check(cancel);PDPage page=document.getPage(i);
                if(page.getRotation()!=0) {page.setRotation(0);r.warnings.add("page_rotation_normalized:"+(i+1));}
                Collector stripper=new Collector(cancel);
                stripper.setStartPage(i+1);stripper.setEndPage(i+1);
                stripper.setSortByPosition(true);stripper.setSuppressDuplicateOverlappingText(false);
                stripper.setLineSeparator("\n");stripper.setParagraphStart("\n");stripper.setParagraphEnd("\n");
                stripper.setPageStart("");stripper.setPageEnd("");stripper.setArticleStart("");stripper.setArticleEnd("");
                String text=stripper.getText(document).trim();
                GraphicsProbe probe=new GraphicsProbe(page,cancel);probe.processPage(page);
                if(text.isEmpty()) {
                    if(probe.images>0) missing=true;
                    else if(probe.paint>0) graphicsOnly=true;
                    else r.blankPages.add(i+1);
                } else {
                    if(probe.images>0) r.warnings.add("contains_illustration:"+(i+1));
                    if(probe.imageCoverage>.15) missing=true;
                    if(probe.strokes>2 || stripper.layoutRisk()) r.warnings.add("layout_review:"+(i+1));
                    if(text.indexOf('\ufffd')>=0 || text.matches("(?s).*[\\uE000-\\uF8FF].*") || text.contains("(cid:")) r.warnings.add("font_mapping_review:"+(i+1));
                }
                r.pages.add(text); progress.page(i+1,count);check(cancel);
            }
            // Never return a successful partial document when any page requires OCR/review.
            if(missing) throw new Failure("NEEDS_OCR");
            if(graphicsOnly) throw new Failure("NO_TEXT_WITH_GRAPHICS");
            StringBuilder body=new StringBuilder();
            for(String page:r.pages) if(!page.isEmpty()) {if(body.length()>0) body.append("\n\n");body.append(page);}
            r.body=body.toString();if(r.body.isEmpty()) throw new Failure("EMPTY_DOCUMENT");
            return r;
        } catch(InvalidPasswordException e) {throw new Failure("PASSWORD_REQUIRED");}
        catch(Failure e) {throw e;}
        catch(IOException | RuntimeException e) {check(cancel);throw new Failure("INVALID_PDF");}
    }
    static final class NativePage {
        String body;int images,paint;double imageCoverage;
        final List<String> warnings=new ArrayList<>();
    }
    static NativePage nativePage(PDDocument document,int index,AtomicBoolean cancel) throws IOException {
        PDPage page=document.getPage(index);page.setRotation(0);
        Collector stripper=new Collector(cancel);stripper.setStartPage(index+1);stripper.setEndPage(index+1);
        stripper.setSortByPosition(true);stripper.setSuppressDuplicateOverlappingText(false);
        stripper.setLineSeparator("\n");stripper.setParagraphStart("\n");stripper.setParagraphEnd("\n");
        stripper.setPageStart("");stripper.setPageEnd("");stripper.setArticleStart("");stripper.setArticleEnd("");
        NativePage result=new NativePage();result.body=stripper.getText(document).trim();
        GraphicsProbe probe=new GraphicsProbe(page,cancel);probe.processPage(page);
        result.images=probe.images;result.paint=probe.paint;result.imageCoverage=probe.imageCoverage;
        if(probe.strokes>2||stripper.layoutRisk())result.warnings.add("native_layout_requires_review");
        if(result.body.indexOf('\ufffd')>=0||result.body.matches("(?s).*[\\uE000-\\uF8FF].*")||result.body.contains("(cid:"))result.warnings.add("font_mapping_requires_review");
        return result;
    }
    private static final class Collector extends PDFTextStripper {
        final List<TextPosition> positions=new ArrayList<>();final AtomicBoolean cancel;
        Collector(AtomicBoolean cancel) throws IOException {this.cancel=cancel;}
        @Override protected void processTextPosition(TextPosition p) {
            if(cancel.get()) throw new java.util.concurrent.CancellationException();
            positions.add(p);super.processTextPosition(p);
        }
        boolean layoutRisk() {
            List<TextPosition> sorted=new ArrayList<>(positions);
            sorted.sort(Comparator.comparingDouble((TextPosition p)->Math.round(p.getYDirAdj()/3)).thenComparingDouble(TextPosition::getXDirAdj));
            int gaps=0;
            for(int i=1;i<sorted.size();i++) {
                TextPosition a=sorted.get(i-1),b=sorted.get(i);
                if(Math.abs(a.getYDirAdj()-b.getYDirAdj())<3 && b.getXDirAdj()-a.getXDirAdj()-a.getWidthDirAdj()>60) gaps++;
            }
            return gaps>=3;
        }
    }
    private static final class GraphicsProbe extends PDFGraphicsStreamEngine {
        int images=0,paint=0,strokes=0;double imageCoverage=0;PointF point=new PointF();final AtomicBoolean cancel;
        GraphicsProbe(PDPage page,AtomicBoolean cancel){super(page);this.cancel=cancel;}
        @Override public void drawImage(PDImage image) throws IOException {
            check(cancel);images++;
            com.tom_roush.pdfbox.util.Matrix m=getGraphicsState().getCurrentTransformationMatrix();
            double area=Math.abs(m.getScaleX()*m.getScaleY()-m.getShearX()*m.getShearY());
            imageCoverage+=area/(getPage().getCropBox().getWidth()*getPage().getCropBox().getHeight());
        }
        @Override public void appendRectangle(PointF a,PointF b,PointF c,PointF d){point=a;}
        @Override public void clip(android.graphics.Path.FillType windingRule){}
        @Override public void moveTo(float x,float y){point=new PointF(x,y);}
        @Override public void lineTo(float x,float y){point=new PointF(x,y);}
        @Override public void curveTo(float x1,float y1,float x2,float y2,float x3,float y3){point=new PointF(x3,y3);}
        @Override public PointF getCurrentPoint(){return point;}
        @Override public void closePath(){}
        @Override public void endPath(){}
        @Override public void strokePath(){paint++;strokes++;}
        @Override public void fillPath(android.graphics.Path.FillType rule){paint++;}
        @Override public void fillAndStrokePath(android.graphics.Path.FillType rule){paint++;strokes++;}
        @Override public void shadingFill(com.tom_roush.pdfbox.cos.COSName name){paint++;}
    }
}
