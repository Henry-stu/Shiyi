package com.hh.recall.importing;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline PNG and scanned PDF extraction. Recognized words are preserved for user review. */
public final class OcrImporter implements AutoCloseable {
    public interface Progress {void update(String stage,int completed,int total);}
    public static final class Failure extends TextImporter.Failure {
        public final JSONArray pages;
        Failure(String code,JSONArray pages){super(code);this.pages=pages;}
    }
    public static final class Result implements AutoCloseable {
        public String body="";public final JSONArray pages=new JSONArray();
        public final List<String> warnings=new ArrayList<>();
        public final List<Bitmap> previews=new ArrayList<>();
        @Override public void close(){for(Bitmap b:previews)if(b!=null&&!b.isRecycled())b.recycle();previews.clear();}
        public JSONObject json() throws JSONException{return new JSONObject().put("body",body).put("pages",pages).put("warnings",new JSONArray(warnings)).put("state","review_required");}
    }
    private final Context app;
    private final boolean capturePreviews;
    private OrtOcr ort;
    public int calls=0;
    public OcrImporter(Context context){this(context,false);}
    public OcrImporter(Context context,boolean capture){app=context.getApplicationContext();capturePreviews=capture;}
    @Override public void close(){
        if(ort!=null){ort.close();ort=null;}
    }
    private void recycleWhenSafe(Bitmap bitmap){
        // ONNX calls are synchronous; no native operation retains this bitmap after infer returns.
        if(!bitmap.isRecycled())bitmap.recycle();
    }
    private static void check(AtomicBoolean cancel) throws TextImporter.Failure {
        if(cancel.get()||Thread.currentThread().isInterrupted())throw new TextImporter.Failure("CANCELLED");
    }
    static final class Line {
        String text;float score,angle;RectF box;
        Line(String t,float s,float a,RectF b){text=t;score=s;angle=a;box=b;}
        Line shifted(float dy){RectF b=new RectF(box);b.offset(0,dy);return new Line(text,score,angle,b);}
        JSONObject json() throws JSONException{return new JSONObject().put("text",text).put("confidence",score).put("angle",angle).put("box",new JSONArray(new float[]{box.left,box.top,box.right,box.bottom}));}
    }
    private List<Line> infer(Bitmap image,AtomicBoolean cancel) throws Exception {
        check(cancel);if(ort==null)ort=new OrtOcr(app);calls++;return ort.infer(image,cancel);
    }
    private static float median(List<Float> values){if(values.isEmpty())return 12;Collections.sort(values);return values.get(values.size()/2);}
    private static int alnum(String s){int n=0;for(int i=0;i<s.length();){int c=s.codePointAt(i);if(Character.isLetterOrDigit(c))n++;i+=Character.charCount(c);}return n;}
    private static double quality(List<Line> lines){
        double total=0;for(Line l:lines){boolean upright=Math.cos(Math.toRadians(l.angle))>.6&&l.box.width()>=l.box.height();
            total+=alnum(l.text)*(l.score>0?l.score:.5)*(upright?1:.12);}
        return total;
    }
    private static Bitmap rotated(Bitmap image,int degrees){if(degrees==0)return image;Matrix matrix=new Matrix();matrix.postRotate(degrees);return Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),matrix,true);}
    private static Bitmap straightened(Bitmap image,float degrees){
        Matrix matrix=new Matrix();matrix.setRotate(degrees);RectF bounds=new RectF(0,0,image.getWidth(),image.getHeight());matrix.mapRect(bounds);matrix.postTranslate(-bounds.left,-bounds.top);
        Bitmap result=Bitmap.createBitmap((int)Math.ceil(bounds.width()),(int)Math.ceil(bounds.height()),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(image,matrix,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));return result;
    }
    private static Bitmap maxEdge(Bitmap image,int edge){float ratio=Math.min(1f,edge/(float)Math.max(image.getWidth(),image.getHeight()));
        return ratio==1?image:Bitmap.createScaledBitmap(image,Math.max(1,Math.round(image.getWidth()*ratio)),Math.max(1,Math.round(image.getHeight()*ratio)),true);}
    private static Bitmap decodePng(byte[] data) throws TextImporter.Failure {
        byte[] sig={(byte)137,80,78,71,13,10,26,10};if(data.length<8)throw new TextImporter.Failure("INVALID_IMAGE");
        for(int i=0;i<8;i++)if(data[i]!=sig[i])throw new TextImporter.Failure("INVALID_IMAGE");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(options.outWidth<=0||options.outHeight<=0)throw new TextImporter.Failure("INVALID_IMAGE");
        if((long)options.outWidth*options.outHeight>24_000_000L)throw new TextImporter.Failure("PIXEL_LIMIT");
        options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;
        while((long)options.outWidth*options.outHeight/options.inSampleSize/options.inSampleSize>8_000_000)options.inSampleSize*=2;
        Bitmap image=BitmapFactory.decodeByteArray(data,0,data.length,options);if(image==null)throw new TextImporter.Failure("INVALID_IMAGE");return image;
    }
    private static Bitmap thumbnail(Bitmap source){
        // PNG comparison keeps the bounded decoded image's detail for full-screen zoom.
        Bitmap small=source,white=Bitmap.createBitmap(small.getWidth(),small.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(white);canvas.drawColor(Color.WHITE);canvas.drawBitmap(small,0,0,null);if(small!=source)small.recycle();return white;
    }
    private static final class Prepared {Bitmap image;boolean blank,inverted,noiseCandidate;}
    private static Prepared prepare(Bitmap source,AtomicBoolean cancel) throws Exception {
        int w=source.getWidth(),h=source.getHeight();int[] pixels=new int[w*h];source.getPixels(pixels,0,w,0,0,w,h);
        int[] hist=new int[256];
        for(int i=0;i<pixels.length;i++){
            if(i%65536==0)check(cancel);int p=pixels[i],a=Color.alpha(p);
            int r=(Color.red(p)*a+255*(255-a))/255,g=(Color.green(p)*a+255*(255-a))/255,b=(Color.blue(p)*a+255*(255-a))/255;
            int y=(77*r+150*g+29*b)>>8;pixels[i]=y;hist[y]++;
        }
        int lo=0,hi=255;while(lo<255&&hist[lo]==0)lo++;while(hi>0&&hist[hi]==0)hi--;
        Prepared result=new Prepared();result.blank=hi-lo<=1;
        if(!result.blank)result.noiseCandidate=noisyBlank(pixels,w,h,hist);
        long dark=0;for(int i=0;i<90;i++)dark+=hist[i];result.inverted=dark>pixels.length*.65;
        for(int i=0;i<pixels.length;i++){
            int y=pixels[i];if(hi-lo>1)y=(y-lo)*255/(hi-lo);if(result.inverted)y=255-y;pixels[i]=Color.rgb(y,y,y);
        }
        result.image=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);return result;
    }
    private static boolean noisyBlank(int[] gray,int w,int h,int[] histogram){
        if(w>1200){
            int sw=1200,sh=Math.max(1,Math.round(h*1200f/w));int[] reduced=new int[sw*sh],hist=new int[256];
            // Average source pixels instead of sampling a single pixel of a noisy page.
            for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){
                int x0=Math.round(x*w/(float)sw),x1=Math.max(x0+1,Math.round((x+1)*w/(float)sw));
                int y0=Math.round(y*h/(float)sh),y1=Math.max(y0+1,Math.round((y+1)*h/(float)sh));int sum=0,count=0;
                for(int sy=y0;sy<Math.min(h,y1);sy++)for(int sx=x0;sx<Math.min(w,x1);sx++){sum+=gray[sy*w+sx];count++;}
                int value=Math.round(sum/(float)Math.max(1,count));reduced[y*sw+x]=value;hist[value]++;
            }
            gray=reduced;histogram=hist;w=sw;h=sh;
        }
        int n=gray.length,total=0,p1=0,p99=255,med=255;
        for(int i=0;i<256;i++){total+=histogram[i];if(total>=n*.01){p1=i;break;}}
        total=0;for(int i=0;i<256;i++){total+=histogram[i];if(total>=n*.99){p99=i;break;}}
        if(p99-p1>12)return false;
        total=0;for(int i=0;i<256;i++){total+=histogram[i];if(total>=n*.5){med=i;break;}}
        // Conservative holdout-tested heuristic: keep any connected dark component > 6 pixels.
        int scale=Math.max(1,w/1200),sw=(w+scale-1)/scale,sh=(h+scale-1)/scale;boolean[] ink=new boolean[sw*sh];int count=0;
        for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){int k=y*sw+x;ink[k]=gray[Math.min(h-1,y*scale)*w+Math.min(w-1,x*scale)]<med-6;if(ink[k])count++;}
        if(count>ink.length*.001)return false;
        int[] stack=new int[ink.length];
        for(int k=0;k<ink.length;k++)if(ink[k]){
            int end=1,head=0;stack[0]=k;ink[k]=false;
            while(head<end){int at=stack[head++],x=at%sw,y=at/sw;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int nx=x+dx,ny=y+dy;if(nx<0||nx>=sw||ny<0||ny>=sh)continue;int next=ny*sw+nx;
                    if(ink[next]){ink[next]=false;stack[end++]=next;if(end>6)return false;}
                }
            }
        }
        return true;
    }
    private static boolean grid(Bitmap image){
        Bitmap small=maxEdge(image,1600);int w=small.getWidth(),h=small.getHeight();int[] px=new int[w*h];small.getPixels(px,0,w,0,0,w,h);
        int[] ys=new int[h],xs=new int[w];for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(Color.red(px[y*w+x])<110){ys[y]++;xs[x]++;}
        int rows=0,cols=0;boolean was=false;for(int v:ys){boolean now=v>w*.4;if(now&&!was)rows++;was=now;}was=false;
        for(int v:xs){boolean now=v>h*.4;if(now&&!was)cols++;was=now;}if(small!=image)small.recycle();return rows>=3&&cols>=2;
    }
    private static List<List<Line>> columns(List<Line> lines,int width){
        List<List<Line>> best=null;float bestGap=0;
        for(float fraction=.25f;fraction<=.751f;fraction+=.025f){
            float cut=width*fraction;List<Line> left=new ArrayList<>(),right=new ArrayList<>(),head=new ArrayList<>();
            for(Line l:lines){if(l.box.right<cut)left.add(l);else if(l.box.left>cut)right.add(l);else head.add(l);}
            if(Math.min(left.size(),right.size())<3)continue;
            float lt=Float.MAX_VALUE,lb=0,lr=0,rt=Float.MAX_VALUE,rb=0,rl=Float.MAX_VALUE;
            for(Line l:left){lt=Math.min(lt,l.box.top);lb=Math.max(lb,l.box.bottom);lr=Math.max(lr,l.box.right);}
            for(Line l:right){rt=Math.min(rt,l.box.top);rb=Math.max(rb,l.box.bottom);rl=Math.min(rl,l.box.left);}
            boolean crosses=false;for(Line l:head)if(l.box.bottom>Math.min(lt,rt))crosses=true;
            float gap=rl-lr,overlap=Math.max(0,Math.min(lb,rb)-Math.max(lt,rt))/Math.max(1,Math.min(lb-lt,rb-rt));
            if(!crosses&&gap>width*.07&&gap>bestGap&&overlap>.6){bestGap=gap;best=Arrays.asList(head,left,right);}
        }
        return best;
    }
    private static String join(List<Line> lines,Bitmap image,List<String> warnings){
        boolean table=grid(image);if(table)warnings.add("table_layout_requires_review");
        List<List<Line>> blocks=table?null:columns(lines,image.getWidth());if(blocks!=null)warnings.add("two_columns_heuristic_requires_review");else blocks=Collections.singletonList(lines);
        List<Float> heights=new ArrayList<>();for(Line l:lines)heights.add(l.box.height());float size=median(heights),tolerance=Math.max(4,size*.45f);
        StringBuilder body=new StringBuilder();
        for(List<Line> block:blocks){
            if(block.isEmpty())continue;block.sort(Comparator.comparingDouble((Line l)->l.box.centerY()).thenComparingDouble(l->l.box.left));
            List<List<Line>> rows=new ArrayList<>();float cy=-Float.MAX_VALUE;
            for(Line l:block){if(rows.isEmpty()||Math.abs(l.box.centerY()-cy)>tolerance){rows.add(new ArrayList<>());cy=l.box.centerY();}rows.get(rows.size()-1).add(l);}
            if(body.length()>0)body.append("\n\n");float bottom=-1;
            for(List<Line> row:rows){row.sort(Comparator.comparingDouble(l->l.box.left));float top=Float.MAX_VALUE,newBottom=0;
                for(Line l:row){top=Math.min(top,l.box.top);newBottom=Math.max(newBottom,l.box.bottom);}
                if(bottom>=0)body.append(top-bottom>size*1.5?"\n\n":"\n");
                for(int i=0;i<row.size();i++){if(i>0)body.append(' ');body.append(row.get(i).text);}bottom=newBottom;
            }
        }
        return body.toString();
    }
    private static void merge(List<Line> all,Line line){
        for(int i=0;i<all.size();i++){
            Line previous=all.get(i);RectF overlap=new RectF(previous.box);
            if(!overlap.intersect(line.box))continue;
            float intersection=overlap.width()*overlap.height(),union=previous.box.width()*previous.box.height()+line.box.width()*line.box.height()-intersection;
            if(intersection/Math.max(1,union)>.35){
                if(line.text.length()*(line.score>0?line.score:.5)>previous.text.length()*(previous.score>0?previous.score:.5))all.set(i,line);return;
            }
        }
        all.add(line);
    }
    private JSONObject image(Bitmap source,int page,AtomicBoolean cancel,Progress progress) throws Exception {
        Prepared prepared=prepare(source,cancel);Bitmap normal=prepared.image;JSONObject out=new JSONObject().put("number",page).put("route","ocr");
        List<String> warnings=new ArrayList<>();if(prepared.inverted)warnings.add("dark_background_inverted");
        try{
            if(prepared.blank)return out.put("blank",true).put("body","").put("warnings",new JSONArray().put("blank_heuristic_requires_review"));
            Bitmap probe=maxEdge(normal,1800);List<List<Line>> candidates=new ArrayList<>();JSONArray qualities=new JSONArray();int chosen=0;double best=-1,second=-1;
            try{
                for(int rotation=0;rotation<4;rotation++){
                    check(cancel);progress.update("方向检查 "+(rotation*90)+"°",page-1,0);Bitmap rotated=rotated(probe,rotation*90);
                    List<Line> lines;
                    try{lines=infer(rotated,cancel);}finally{if(rotated!=probe)recycleWhenSafe(rotated);}
                    candidates.add(lines);double score=quality(lines);qualities.put(new JSONObject().put("rotation",rotation*90).put("score",score));
                    if(score>best){second=best;best=score;chosen=rotation;}else second=Math.max(second,score);
                }
                if(best>0&&second>best*.92)warnings.add("orientation_ambiguous_requires_review");
                out.put("chosen_rotation",chosen*90).put("orientation_candidates",qualities);
                Bitmap upright=rotated(normal,chosen*90);List<Line> lines;
                try{
                    boolean longImage=upright.getHeight()>2600&&upright.getHeight()>upright.getWidth()*2;
                    lines=probe==normal||longImage?candidates.get(chosen):infer(upright,cancel);
                    List<Float> angles=new ArrayList<>();for(Line line:lines)if(line.box.width()>line.box.height()&&Math.abs(line.angle)<14)angles.add(line.angle);
                    if(angles.size()>=3){
                        float angle=median(angles);
                        if(Math.abs(angle)>=1.2f&&Math.abs(angle)<=12){
                            Bitmap straight=straightened(upright,-angle);boolean accepted=false;
                            try{
                                List<Line> corrected=infer(straight,cancel);
                                if(quality(corrected)>=quality(lines)*.9){
                                    if(upright!=normal)recycleWhenSafe(upright);upright=straight;lines=corrected;accepted=true;out.put("deskew_degrees",-angle);
                                }else warnings.add("deskew_not_applied");
                            }finally{if(!accepted)recycleWhenSafe(straight);}
                        }
                    }
                    if(upright.getHeight()>2600&&upright.getHeight()>upright.getWidth()*2){
                        lines=new ArrayList<>();
                        for(int y=0;y<upright.getHeight();y+=1500){
                            check(cancel);int height=Math.min(1700,upright.getHeight()-y);Bitmap tile=Bitmap.createBitmap(upright,0,y,upright.getWidth(),height);
                            progress.update("长图分片 "+(y/1500+1),page-1,0);
                            try{for(Line line:infer(tile,cancel)){
                                if(y>0&&line.box.top<12)continue;if(y+height<upright.getHeight()&&line.box.bottom>height-12)continue;merge(lines,line.shifted(y));
                            }}finally{if(tile!=upright)recycleWhenSafe(tile);}
                            if(y+height==upright.getHeight())break;
                        }
                        warnings.add("long_image_geometry_overlap_merge");
                    }
                    String body=join(lines,upright,warnings);JSONArray boxes=new JSONArray();for(Line line:lines)boxes.put(line.json());
                    boolean text=alnum(body)>0;
                    // A noise heuristic alone must never suppress text that OCR can actually recognize.
                    if(!text&&prepared.noiseCandidate)return out.put("blank",true).put("body","").put("warnings",new JSONArray().put("blank_heuristic_requires_review"));
                    out.put("blank",false).put("body",text?body:"").put("no_text",!text).put("diagnostic_text",text?"":body).put("lines",boxes).put("warnings",new JSONArray(warnings));
                    return out;
                }finally{if(upright!=normal)recycleWhenSafe(upright);}
            }finally{if(probe!=normal)recycleWhenSafe(probe);}
        }finally{recycleWhenSafe(normal);}
    }
    public Result read(InputStream input,String family,AtomicBoolean cancel,Progress progress) throws Exception {
        byte[] data=TextImporter.boundedRead(input,cancel,32*1024*1024);check(cancel);Result result=new Result();
        if("png".equals(family)){
            Bitmap source=decodePng(data);JSONObject page;
            try{
                page=image(source,1,cancel,progress);if(capturePreviews)result.previews.add(thumbnail(source));
                result.pages.put(page);check(cancel);
                if(page.optBoolean("blank"))throw new Failure("EMPTY_DOCUMENT",result.pages);
                if(page.optBoolean("no_text"))throw new Failure("NO_TEXT",result.pages);
                result.body=page.getString("body");result.warnings.add("ocr_requires_manual_review");progress.update("图片完成",1,1);check(cancel);return result;
            }catch(Exception e){result.close();throw e;}finally{source.recycle();}
        }
        if(!"pdf".equals(family))throw new TextImporter.Failure("UNSUPPORTED_TYPE");
        PDFBoxResourceLoader.init(app);
        File temp=null;PdfRenderer renderer=null;ParcelFileDescriptor descriptor=null;
        try(PDDocument doc=PDDocument.load(data)){
            if(doc.isEncrypted())throw new Failure("PASSWORD_REQUIRED",result.pages);
            int count=doc.getNumberOfPages();if(count>30)throw new Failure("PAGE_LIMIT",result.pages);
            boolean incomplete=false;
            for(int i=0;i<count;i++){
                check(cancel);progress.update("检查页面",i,count);TextImporter.NativePage nativePage=TextImporter.nativePage(doc,i,cancel);JSONObject page;
                if(!nativePage.body.isEmpty()&&nativePage.imageCoverage<.15){
                    page=new JSONObject().put("number",i+1).put("route","native").put("body",nativePage.body).put("blank",false);
                    if(nativePage.images>0)nativePage.warnings.add("contains_illustration_requires_review");
                    page.put("warnings",new JSONArray(nativePage.warnings));
                    if(capturePreviews)result.previews.add(null);
                }else if(nativePage.body.isEmpty()&&nativePage.images==0&&nativePage.paint==0){
                    page=new JSONObject().put("number",i+1).put("route","blank_native").put("blank",true).put("body","");
                    if(capturePreviews)result.previews.add(null);
                }else{
                    if(renderer==null){
                        temp=File.createTempFile("ocr-render-",".pdf",app.getCacheDir());try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);}
                        descriptor=ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);
                        if(renderer.getPageCount()!=count)throw new Failure("PAGE_COUNT_MISMATCH",result.pages);
                    }
                    Bitmap bitmap;
                    try(PdfRenderer.Page pdfPage=renderer.openPage(i)){
                        double scale=Math.min(2.0,2400.0/Math.max(pdfPage.getWidth(),pdfPage.getHeight()));
                        int w=Math.max(1,(int)Math.round(pdfPage.getWidth()*scale)),h=Math.max(1,(int)Math.round(pdfPage.getHeight()*scale));
                        bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                        pdfPage.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                    try{check(cancel);progress.update("识别扫描页",i,count);page=image(bitmap,i+1,cancel,progress);if(capturePreviews)result.previews.add(thumbnail(bitmap));}finally{bitmap.recycle();}
                    if(page.optBoolean("no_text"))incomplete=true;
                }
                result.pages.put(page);progress.update("页面完成",i+1,count);check(cancel);
            }
            if(incomplete)throw new Failure("INCOMPLETE_OCR",result.pages);
            StringBuilder body=new StringBuilder();for(int i=0;i<result.pages.length();i++){
                String text=result.pages.getJSONObject(i).optString("body","");if(!text.isEmpty()){if(body.length()>0)body.append("\n\n");body.append(text);}
            }
            if(body.length()==0)throw new Failure("EMPTY_DOCUMENT",result.pages);
            result.body=body.toString();result.warnings.add("ocr_requires_manual_review");return result;
        }catch(InvalidPasswordException e){result.close();throw new Failure("PASSWORD_REQUIRED",result.pages);}
        catch(Failure e){result.close();throw e;}
        catch(TextImporter.Failure e){result.close();throw new Failure(e.code,result.pages);}
        catch(Exception e){result.close();if(cancel.get())throw new Failure("CANCELLED",result.pages);throw new Failure(e instanceof IOException?"INVALID_PDF":"OCR_FAILED",result.pages);}
        finally{if(renderer!=null)renderer.close();else if(descriptor!=null)descriptor.close();if(temp!=null&&!temp.delete())temp.deleteOnExit();}
    }
}
