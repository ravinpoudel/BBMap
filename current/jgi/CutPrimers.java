package jgi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

import align2.ListNum;
import align2.Tools;

import stream.ConcurrentGenericReadInputStream;
import stream.ConcurrentReadStreamInterface;
import stream.RTextOutputStream3;
import stream.Read;
import stream.SamLine;

import dna.Parser;
import dna.Timer;

import fileIO.FileFormat;
import fileIO.ReadWrite;
import fileIO.TextFile;

/**
 * Uses two sam files defining primer mapping locations to cut the primed sequence out of the reference.
 * @author Brian Bushnell
 * @date Nov 24, 2014
 *
 */
public class CutPrimers {

	public static void main(String[] args){
		Timer t=new Timer();
		t.start();
		CutPrimers as=new CutPrimers(args);
		as.process(t);
	}
	
	public CutPrimers(String[] args){
		
		if(Parser.parseHelp(args)){
			printOptions();
			System.exit(0);
		}
		
		outstream.println("Executing "+getClass().getName()+" "+Arrays.toString(args)+"\n");
		
		Parser parser=new Parser();
		for(int i=0; i<args.length; i++){
			String arg=args[i];
			String[] split=arg.split("=");
			String a=split[0].toLowerCase();
			String b=split.length>1 ? split[1] : null;
			if(b==null || b.equalsIgnoreCase("null")){b=null;}
			while(a.startsWith("-")){a=a.substring(1);} //In case people use hyphens

			if(parser.parse(arg, a, b)){
				//do nothing
			}else if(a.equals("parse_flag_goes_here")){
				//Set a variable here
			}else if(a.equals("sam1")){
				sam1=b;
			}else if(a.equals("sam2")){
				sam2=b;
			}else if(a.equals("fake") || a.equals("addfake")){
				ADD_FAKE_READS=Tools.parseBoolean(b);
			}else{
				outstream.println("Unknown parameter "+args[i]);
				assert(false) : "Unknown parameter "+args[i];
				//				throw new RuntimeException("Unknown parameter "+args[i]);
			}
		}
		
		{//Download parser fields
			maxReads=parser.maxReads;
			in1=parser.in1;
			out1=parser.out1;
		}
		
		ffout1=FileFormat.testOutput(out1, FileFormat.FASTQ, null, true, true, false, false);
		ffin1=FileFormat.testInput(in1, FileFormat.FASTQ, null, true, true);
	}
	
	void process(Timer t){
		
		final ConcurrentReadStreamInterface cris;
		{
			cris=ConcurrentGenericReadInputStream.getReadInputStream(maxReads, false, false, ffin1, null);
			if(verbose){outstream.println("Started cris");}
			final Thread cristhread=new Thread(cris);
			cristhread.start();
		}
		boolean paired=cris.paired();

		final RTextOutputStream3 ros;
		if(out1!=null){
			final int buff=4;
			
			if(cris.paired() && (in1==null || !in1.contains(".sam"))){
				outstream.println("Writing interleaved.");
			}			

			assert(!out1.equalsIgnoreCase(in1) && !out1.equalsIgnoreCase(in1)) : "Input file and output file have same name.";
			
			ros=new RTextOutputStream3(ffout1, null, buff, null, false);
			ros.start();
		}else{ros=null;}

		LinkedHashMap<String, SamLine> p1set=toSamLines(sam1);
		LinkedHashMap<String, SamLine> p2set=toSamLines(sam2);
		long readsProcessed=0, readsSuccess=0;
		{
			
			ListNum<Read> ln=cris.nextList();
			ArrayList<Read> reads=(ln!=null ? ln.list : null);
			
			if(reads!=null && !reads.isEmpty()){
				Read r=reads.get(0);
				assert((ffin1==null || ffin1.samOrBam()) || (r.mate!=null)==cris.paired());
			}
			
			while(reads!=null && reads.size()>0){
				ArrayList<Read> readsOut=new ArrayList<Read>(reads.size());
				if(verbose){outstream.println("Fetched "+reads.size()+" reads.");}
				
				for(int idx=0; idx<reads.size(); idx++){
					readsProcessed++;
					final Read r=reads.get(idx);

					final SamLine sl1=p1set.get(r.id);
					final SamLine sl2=p2set.get(r.id);
					
					int oldSize=readsOut.size();
					
					if(sl1!=null && sl2!=null){
						final int a1=sl1.start(), b1=sl1.stop();
						final int a2=sl2.start(), b2=sl2.stop();
						if(Tools.overlap(a1, b1, a2, b2)){
							
						}else{
							final byte[] bases, quals;
							if(a1<a2){
								bases=Arrays.copyOfRange(r.bases, b1+1, a2);
								quals=(r.quality==null ? null : Arrays.copyOfRange(r.quality, b1+1, a2));
							}else{
								bases=Arrays.copyOfRange(r.bases, b2+1, a1);
								quals=(r.quality==null ? null : Arrays.copyOfRange(r.quality, b2+1, a1));
							}
							readsOut.add(new Read(bases, -1, (byte)0, -1, -1, r.id, quals, false, r.numericID));
							readsSuccess++;
						}
					}
					
					if(oldSize==readsOut.size() && ADD_FAKE_READS){
						readsOut.add(new Read(new byte[] {'N'}, -1, (byte)0, -1, -1, r.id, null, false, r.numericID));
					}
				}
				
				if(ros!=null){ros.add(readsOut, ln.id);}

				cris.returnList(ln, ln.list.isEmpty());
				if(verbose){outstream.println("Returned a list.");}
				ln=cris.nextList();
				reads=(ln!=null ? ln.list : null);
			}
			if(ln!=null){
				cris.returnList(ln, ln.list==null || ln.list.isEmpty());
			}
		}
		ReadWrite.closeStreams(cris, ros);
		if(verbose){outstream.println("Finished.");}
		
		t.stop();
		outstream.println("Time:                         \t"+t);
		outstream.println("Reads Processed:      "+readsProcessed+" \t"+String.format("%.2fk reads/sec", (readsProcessed/(double)(t.elapsed))*1000000));
		outstream.println("Sequences Generated:  "+readsSuccess);
	}
	
	public static LinkedHashMap<String, SamLine> toSamLines(String fname){
		TextFile tf=new TextFile(fname);
		LinkedHashMap<String, SamLine> list=new LinkedHashMap<String, SamLine>();
		for(String s=tf.nextLine(); s!=null; s=tf.nextLine()){
			if(!s.startsWith("@")){
				SamLine sl=new SamLine(s);
				list.put(new String(sl.rname()), sl);
			}
		}
		tf.close();
		return list;
	}
	
	/*--------------------------------------------------------------*/
	
	private void printOptions(){
		throw new RuntimeException("printOptions: TODO");
	}
	
	/*--------------------------------------------------------------*/
	
	private String in1=null;
	private String sam1=null;
	private String sam2=null;
	private String out1=null;
	
	private boolean ADD_FAKE_READS=true;
	
	private final FileFormat ffin1;
	private final FileFormat ffout1;
	
	/*--------------------------------------------------------------*/

	private long maxReads=-1;
	
	/*--------------------------------------------------------------*/
	
	private java.io.PrintStream outstream=System.err;
	public static boolean verbose=false;
	
}
