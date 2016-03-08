package jgi;

import java.util.ArrayList;
import java.util.Arrays;

import align2.BandedAligner;
import align2.ListNum;
import align2.Shared;
import align2.Tools;

import stream.ConcurrentCollectionReadInputStream;
import stream.ConcurrentGenericReadInputStream;
import stream.ConcurrentReadStreamInterface;
import stream.FASTQ;
import stream.Read;

import dna.Parser;
import dna.Timer;

import fileIO.FileFormat;
import fileIO.ReadWrite;
import fileIO.TextStreamWriter;

/**
 * Calculates an all-to-all identity matrix.
 * @author Brian Bushnell
 * @date Nov 23, 2014
 *
 */
public class IdentityMatrix {

	public static void main(String[] args){
		Timer t=new Timer();
		t.start();
		IdentityMatrix as=new IdentityMatrix(args);
		as.process(t);
	}
	
	public IdentityMatrix(String[] args){
		
		if(Parser.parseHelp(args)){
			printOptions();
			System.exit(0);
		}
		
		outstream.println("Executing "+getClass().getName()+" "+Arrays.toString(args)+"\n");
		
		FileFormat.PRINT_WARNING=false;
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
			}else if(a.equals("edits") || a.equals("maxedits")){
				maxEdits=Integer.parseInt(b);
			}else if(a.equals("percent")){
				percent=Tools.parseBoolean(b);
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
		FASTQ.FORCE_INTERLEAVED=false;
		FASTQ.TEST_INTERLEAVED=false;
		
		ffout1=FileFormat.testOutput(out1, FileFormat.FASTQ, null, true, true, false, false);
		ffin1=FileFormat.testInput(in1, FileFormat.FASTQ, null, true, true);
	}
	
	void process(Timer t){
		
		allReads=load();
		Shared.READ_BUFFER_LENGTH=4;
		ConcurrentCollectionReadInputStream cris=new ConcurrentCollectionReadInputStream(allReads, null, -1);
		final Thread cristhread=new Thread(cris);
		cristhread.start();
		
		
		ArrayList<ProcessThread> threads=new ArrayList<ProcessThread>();
		final int tmax=Tools.max(Shared.THREADS, 1);
		for(int i=0; i<tmax; i++){
			threads.add(new ProcessThread(cris));
		}
		for(ProcessThread pt : threads){pt.start();}
		for(ProcessThread pt : threads){
			while(pt.getState()!=Thread.State.TERMINATED){
				try {
					pt.join();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		ReadWrite.closeStreams(cris);
		
		final int numReads=allReads.size();
		for(int i=1; i<numReads; i++){
			Read r1=allReads.get(i);
			assert(r1.numericID==i);
			for(int j=0; j<i; j++){
				Read r2=allReads.get(j);
				assert(r2.numericID==j);
				((float[])r2.obj)[i]=((float[])r1.obj)[j];
			}
		}
		
		if(ffout1!=null){
			TextStreamWriter tsw=new TextStreamWriter(ffout1);
			tsw.start();
			for(Read r : allReads){
				float[] obj=(float[])r.obj;
				tsw.print(r.id);
				if(percent){
					for(float f : obj){
						tsw.print(String.format("\t%.2f", f));
					}
				}else{
					for(float f : obj){
						tsw.print(String.format("\t%.4f", f));
					}
				}
				tsw.print("\n");
				r.obj=null;
			}
			tsw.poisonAndWait();
		}
		
		t.stop();
		outstream.println("Total Time:                   \t"+t);
		outstream.println("Reads Processed:    "+allReads.size()+" \t"+String.format("%.2fk alignments/sec", (allReads.size()*(long)(allReads.size())/(double)(t.elapsed))*1000000));
	}
	
	private ArrayList<Read> load(){
		Timer t=new Timer();
		t.start();
		final ConcurrentReadStreamInterface cris;
		{
			cris=ConcurrentGenericReadInputStream.getReadInputStream(maxReads, false, false, ffin1, null);
			if(verbose){outstream.println("Started cris");}
			final Thread cristhread=new Thread(cris);
			cristhread.start();
		}
		boolean paired=cris.paired();
		assert(!paired) : "This program is not designed for paired reads.";
		
		long readsProcessed=0;
		int maxLen=0;
		ArrayList<Read> bigList=new ArrayList<Read>();
		{
			
			ListNum<Read> ln=cris.nextList();
			ArrayList<Read> reads=(ln!=null ? ln.list : null);
			
			if(reads!=null && !reads.isEmpty()){
				Read r=reads.get(0);
				assert((ffin1==null || ffin1.samOrBam()) || (r.mate!=null)==cris.paired());
			}
			
			while(reads!=null && reads.size()>0){
				if(verbose){outstream.println("Fetched "+reads.size()+" reads.");}
				
				for(int idx=0; idx<reads.size(); idx++){
					final Read r1=reads.get(idx);
					
					bigList.add(r1);
					maxLen=Tools.max(maxLen, r1.length());
					
					readsProcessed++;
				}
				
				cris.returnList(ln, ln.list.isEmpty());
				if(verbose){outstream.println("Returned a list.");}
				ln=cris.nextList();
				reads=(ln!=null ? ln.list : null);
			}
			if(ln!=null){
				cris.returnList(ln, ln.list==null || ln.list.isEmpty());
			}
		}
		ReadWrite.closeStreams(cris);
		if(verbose){outstream.println("Finished loading "+readsProcessed+" sequences.");}
		
		maxEdits=Tools.min(maxEdits, maxLen);
		
		t.stop();
		outstream.println("Load Time:                    \t"+t);
		
		return bigList;
	}
	
	/*--------------------------------------------------------------*/
	
	private class ProcessThread extends Thread {
		
		ProcessThread(ConcurrentReadStreamInterface cris_){
			cris=cris_;
			bandy=BandedAligner.makeBandedAligner(maxEdits*2+1);
		}
		
		@Override
		public void run(){
			final int numReads=allReads.size();
			ListNum<Read> ln=cris.nextList();
			ArrayList<Read> reads=(ln!=null ? ln.list : null);

			if(reads!=null && !reads.isEmpty()){
				Read r=reads.get(0);
				assert((ffin1==null || ffin1.samOrBam()) || (r.mate!=null)==cris.paired());
			}
			
			while(reads!=null && reads.size()>0){
				if(verbose){outstream.println("Fetched "+reads.size()+" reads.");}

				for(int idx=0; idx<reads.size(); idx++){
					final Read r1=reads.get(idx);
					float[] obj=new float[numReads];
					r1.obj=obj;
					for(Read r2 : allReads){
						if(r2.numericID>r1.numericID){break;}
						int edits=bandy.alignQuadruple(r1.bases, r2.bases, maxEdits, false);
//						System.err.println(r1.id+"->"+r2.id+": Edits="+edits);
						float editRate=edits/(float)Tools.max(r1.length(), r2.length());
						if(percent){
							float id=100*(1-editRate);
							obj[(int)r2.numericID]=id;
						}else{
							obj[(int)r2.numericID]=1-editRate;
						}
					}
				}

				cris.returnList(ln, ln.list.isEmpty());
				if(verbose){outstream.println("Returned a list.");}
				ln=cris.nextList();
				reads=(ln!=null ? ln.list : null);
			}
			if(ln!=null){
				cris.returnList(ln, ln.list==null || ln.list.isEmpty());
			}
		}
		
		private final ConcurrentReadStreamInterface cris;
		private final BandedAligner bandy;
		
	}
	
	/*--------------------------------------------------------------*/
	
	private void printOptions(){
		throw new RuntimeException("printOptions: TODO");
	}
	
	/*--------------------------------------------------------------*/
	
	private String in1=null;
	private String out1=null;
	
	private final FileFormat ffin1;
	private final FileFormat ffout1;
	private boolean percent=false;
	
	private ArrayList<Read> allReads;
	
	/*--------------------------------------------------------------*/
	
	private long maxReads=-1;
	private int maxEdits=BandedAligner.big;
	
	/*--------------------------------------------------------------*/
	
	private java.io.PrintStream outstream=System.err;
	public static boolean verbose=false;
	
}
