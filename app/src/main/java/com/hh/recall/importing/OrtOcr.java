package com.hh.recall.importing;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import ai.onnxruntime.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;

/** CPU port adapted from RapidOCR (Apache-2.0): DB detection and CTC recognition. See assets/licenses. */
final class OrtOcr implements AutoCloseable {
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private OrtSession detector,recognizer;
    private final List<String> characters=new ArrayList<>();
    OrtOcr(Context context) throws Exception {
        if(!OpenCVLoader.initLocal())throw new IOException("OpenCV unavailable");
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);options.setCPUArenaAllocator(false);
            try{
                detector=env.createSession(model(context,"PP-OCRv6_det_small.onnx").getAbsolutePath(),options);
                recognizer=env.createSession(model(context,"PP-OCRv6_rec_small.onnx").getAbsolutePath(),options);
                String dictionary=recognizer.getMetadata().getCustomMetadata().get("character");
                if(dictionary==null)throw new IOException("Model has no character dictionary");
                characters.add("blank");characters.addAll(Arrays.asList(dictionary.split("\\r?\\n")));characters.add(" ");
            }catch(Exception error){close();throw error;}
        }
    }
    private static File model(Context context,String name) throws IOException {
        String expected=name.contains("_det_")?"090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f":"6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884";
        File dir=new File(context.getNoBackupFilesDir(),"import-models");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Model directory");
        File dest=new File(dir,name);if(dest.isFile()&&expected.equals(digest(dest)))return dest;
        File temp=new File(dir,name+".part");
        try(InputStream in=context.getAssets().open("models/"+name);OutputStream out=new FileOutputStream(temp)){
            byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
        }
        if(!expected.equals(digest(temp))){temp.delete();throw new IOException("Model integrity");}
        if(dest.exists()&&!dest.delete())throw new IOException("Model replacement");
        if(!temp.renameTo(dest))throw new IOException("Model copy");return dest;
    }
    private static String digest(File file) throws IOException {
        try(InputStream in=new FileInputStream(file)){
            MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];int n;
            while((n=in.read(buffer))!=-1)hash.update(buffer,0,n);
            StringBuilder hex=new StringBuilder();for(byte b:hash.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static void check(AtomicBoolean cancelled) throws TextImporter.Failure {
        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new TextImporter.Failure("CANCELLED");
    }
    private static final class Box {
        Point[] points;Box(Point[] p){points=p;}
    }
    List<OcrImporter.Line> infer(Bitmap image,AtomicBoolean cancel) throws Exception {
        check(cancel);Mat rgba=new Mat(),source=new Mat();
        try{
            Utils.bitmapToMat(image,rgba);Imgproc.cvtColor(rgba,source,Imgproc.COLOR_RGBA2BGR);rgba.release();
            List<Box> boxes=detect(source,cancel);List<OcrImporter.Line> result=new ArrayList<>();
            for(Box box:boxes){
                check(cancel);Mat crop=crop(source,box.points);
                try{
                    Recognition text=recognize(crop,cancel);
                    if(text.text.trim().isEmpty()||text.score<.5f)continue;
                    float left=Float.MAX_VALUE,top=left,right=0,bottom=0;
                    for(Point p:box.points){left=Math.min(left,(float)p.x);top=Math.min(top,(float)p.y);right=Math.max(right,(float)p.x);bottom=Math.max(bottom,(float)p.y);}
                    float angle=(float)Math.toDegrees(Math.atan2(box.points[1].y-box.points[0].y,box.points[1].x-box.points[0].x));
                    result.add(new OcrImporter.Line(text.text.trim(),text.score,angle,new RectF(left,top,right,bottom)));
                }finally{crop.release();}
            }
            return result;
        }finally{rgba.release();source.release();}
    }
    private static FloatBuffer normalized(Mat image,int paddedWidth) {
        int w=image.cols(),h=image.rows(),plane=paddedWidth*h;byte[] pixels=new byte[w*h*3];image.get(0,0,pixels);
        float[] tensor=new float[plane*3];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)for(int c=0;c<3;c++)tensor[c*plane+y*paddedWidth+x]=((pixels[(y*w+x)*3+c]&255)/255f-.5f)/.5f;
        return FloatBuffer.wrap(tensor);
    }
    private List<Box> detect(Mat source,AtomicBoolean cancel) throws Exception {
        // RapidOCR default Det.limit_type=min, limit_side_len=736; round to multiples of 32.
        double ratio=Math.max(1,736.0/Math.min(source.rows(),source.cols()));
        int h=Math.max(32,(int)Math.rint((int)(source.rows()*ratio)/32.0)*32),w=Math.max(32,(int)Math.rint((int)(source.cols()*ratio)/32.0)*32);
        // Android test resource guard, reported as a failure rather than silently resizing.
        if((long)w*h>10_000_000L)throw new TextImporter.Failure("DETECTOR_PIXEL_LIMIT");
        Mat resized=new Mat(),mask=new Mat(),probabilities=new Mat(),dilated=new Mat(),hierarchy=new Mat(),kernel=Mat.ones(2,2,CvType.CV_8UC1);
        List<MatOfPoint> contours=new ArrayList<>();
        try{
            Imgproc.resize(source,resized,new Size(w,h));float[] values;
            try(OnnxTensor input=OnnxTensor.createTensor(env,normalized(resized,w),new long[]{1,3,h,w});OrtSession.Result output=detector.run(Collections.singletonMap(detector.getInputNames().iterator().next(),input))){
                OnnxTensor tensor=(OnnxTensor)output.get(0);long[] shape=tensor.getInfo().getShape();h=(int)shape[shape.length-2];w=(int)shape[shape.length-1];
                FloatBuffer buffer=tensor.getFloatBuffer();values=new float[h*w];buffer.get(values);
            }
            check(cancel);probabilities.create(h,w,CvType.CV_32FC1);probabilities.put(0,0,values);
            byte[] binary=new byte[values.length];for(int i=0;i<values.length;i++)binary[i]=values[i]>.3f?(byte)255:0;
            mask.create(h,w,CvType.CV_8UC1);mask.put(0,0,binary);
            Imgproc.dilate(mask,dilated,kernel);Imgproc.findContours(dilated,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);
            List<Box> boxes=new ArrayList<>();
            for(int i=0;i<Math.min(1000,contours.size());i++){
                check(cancel);MatOfPoint2f floating=new MatOfPoint2f(contours.get(i).toArray());RotatedRect rect;
                try{rect=Imgproc.minAreaRect(floating);}finally{floating.release();}
                if(Math.min(rect.size.width,rect.size.height)<3)continue;
                Point[] points=ordered(rect);if(score(probabilities,points)<.5)continue;
                // Unclipping a minimum-area rectangle by a round offset has this enclosing rectangle.
                double distance=rect.size.area()*1.6/(2*(rect.size.width+rect.size.height));
                rect.size.width+=distance*2;rect.size.height+=distance*2;
                if(Math.min(rect.size.width,rect.size.height)<5)continue;points=ordered(rect);
                for(Point p:points){p.x=Math.max(0,Math.min(source.cols()-1,Math.rint(p.x/w*source.cols())));p.y=Math.max(0,Math.min(source.rows()-1,Math.rint(p.y/h*source.rows())));}
                if(distance(points[0],points[1])<=3||distance(points[0],points[3])<=3)continue;
                boxes.add(new Box(points));
            }
            boxes.sort(Comparator.comparingDouble((Box b)->b.points[0].y).thenComparingDouble(b->b.points[0].x));return boxes;
        }finally{for(MatOfPoint c:contours)c.release();resized.release();mask.release();probabilities.release();dilated.release();hierarchy.release();kernel.release();}
    }
    private static double score(Mat probability,Point[] box){
        int left=probability.cols()-1,top=probability.rows()-1,right=0,bottom=0;
        for(Point p:box){left=Math.min(left,Math.max(0,(int)Math.floor(p.x)));right=Math.max(right,Math.min(probability.cols()-1,(int)Math.ceil(p.x)));top=Math.min(top,Math.max(0,(int)Math.floor(p.y)));bottom=Math.max(bottom,Math.min(probability.rows()-1,(int)Math.ceil(p.y)));}
        if(right<left||bottom<top)return 0;Mat mask=Mat.zeros(bottom-top+1,right-left+1,CvType.CV_8UC1),roi=probability.submat(top,bottom+1,left,right+1);Point[] shifted=new Point[4];
        for(int i=0;i<4;i++)shifted[i]=new Point((int)(box[i].x-left),(int)(box[i].y-top));MatOfPoint poly=new MatOfPoint(shifted);
        try{Imgproc.fillPoly(mask,Collections.singletonList(poly),new Scalar(1));return Core.mean(roi,mask).val[0];}finally{poly.release();mask.release();roi.release();}
    }
    private static Point[] ordered(RotatedRect rect){
        Point[] points=new Point[4];rect.points(points);Arrays.sort(points,Comparator.comparingDouble(p->p.x));
        Point tl=points[0].y<points[1].y?points[0]:points[1],bl=points[0].y<points[1].y?points[1]:points[0];
        Point tr=points[2].y<points[3].y?points[2]:points[3],br=points[2].y<points[3].y?points[3]:points[2];return new Point[]{tl,tr,br,bl};
    }
    private static double distance(Point a,Point b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static Mat crop(Mat image,Point[] points){
        int w=Math.max(1,(int)Math.max(distance(points[0],points[1]),distance(points[2],points[3]))),h=Math.max(1,(int)Math.max(distance(points[0],points[3]),distance(points[1],points[2])));
        MatOfPoint2f src=new MatOfPoint2f(points),dest=new MatOfPoint2f(new Point(0,0),new Point(w,0),new Point(w,h),new Point(0,h));Mat transform=Imgproc.getPerspectiveTransform(src,dest),result=new Mat();
        try{Imgproc.warpPerspective(image,result,transform,new Size(w,h),Imgproc.INTER_CUBIC,Core.BORDER_REPLICATE,new Scalar(255,255,255));
            if(h/(double)w>=1.5){Mat rotated=new Mat();Core.rotate(result,rotated,Core.ROTATE_90_COUNTERCLOCKWISE);result.release();result=rotated;}return result;
        }finally{src.release();dest.release();transform.release();}
    }
    private static final class Recognition {String text;float score;Recognition(String t,float s){text=t;score=s;}}
    private Recognition recognize(Mat crop,AtomicBoolean cancel) throws Exception {
        check(cancel);int width=Math.max(320,(int)(48*Math.max(320.0/48,crop.cols()/(double)crop.rows()))),resizedWidth=Math.min(width,(int)Math.ceil(48*crop.cols()/(double)crop.rows()));
        if(width>8192)throw new TextImporter.Failure("RECOGNIZER_WIDTH_LIMIT");Mat resized=new Mat();
        try{
            Imgproc.resize(crop,resized,new Size(resizedWidth,48));
            try(OnnxTensor input=OnnxTensor.createTensor(env,normalized(resized,width),new long[]{1,3,48,width});OrtSession.Result output=recognizer.run(Collections.singletonMap(recognizer.getInputNames().iterator().next(),input))){
                check(cancel);OnnxTensor tensor=(OnnxTensor)output.get(0);long[] shape=tensor.getInfo().getShape();int steps=(int)shape[1],vocab=(int)shape[2];
                if(vocab!=characters.size())throw new IOException("Dictionary size "+characters.size()+" != "+vocab);
                FloatBuffer buffer=tensor.getFloatBuffer();StringBuilder text=new StringBuilder();float confidence=0;int count=0,previous=-1;
                for(int t=0;t<steps;t++){float max=-Float.MAX_VALUE;int index=0;for(int c=0;c<vocab;c++){float value=buffer.get();if(value>max){max=value;index=c;}}
                    if(index!=0&&index!=previous){text.append(characters.get(index));confidence+=max;count++;}previous=index;}
                return new Recognition(text.toString(),count==0?0:confidence/count);
            }
        }finally{resized.release();}
    }
    @Override public void close(){try{if(detector!=null)detector.close();}catch(OrtException ignored){}finally{detector=null;}try{if(recognizer!=null)recognizer.close();}catch(OrtException ignored){}finally{recognizer=null;}}
}
