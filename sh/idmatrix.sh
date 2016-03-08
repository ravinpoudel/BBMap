#!/bin/bash
#idmatrix in=<file> out=<file>

usage(){
echo "
Written by Brian Bushnell
Last modified November 23, 2014

Description:  Generates an identity matrix via all-to-all alignment.

Usage:	idmatrix.sh in=<file> out=<file>

Parameters:

in=<file>           File containing reads. in=stdin.fa will pipe from stdin.
out=<file>          Matrix output. out=stdout will pipe to stdout.
threads=auto        (t) Set number of threads to use; default is number of 
                    logical processors.
percent=f           Output identity as percent rather than a fraction.
edits=              Allow at most this much edit distance.  Default is the
                    length of the longest input sequence. Lower is faster.

Java Parameters:

-Xmx                This will be passed to Java to set memory usage, overriding 
                    the program's automatic memory detection. -Xmx20g will specify 
                    20 gigs of RAM, and -Xmx200m will specify 200 megs.  
                    The max is typically 85% of physical memory.

Please contact Brian Bushnell at bbushnell@lbl.gov if you encounter any problems.
"
}

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )/"
CP="$DIR""current/"

z="-Xmx2g"
z2="-Xms2g"
EA="-ea"
set=0

if [ -z "$1" ] || [[ $1 == -h ]] || [[ $1 == --help ]]; then
	usage
	exit
fi

calcXmx () {
	source "$DIR""/calcmem.sh"
	parseXmx "$@"
	if [[ $set == 1 ]]; then
		return
	fi
	freeRam 3200m 84
	z="-Xmx${RAM}m"
	z2="-Xms${RAM}m"
}
calcXmx "$@"

idmatrix() {
	#module unload oracle-jdk
	#module load oracle-jdk/1.7_64bit
	#module load pigz
	local CMD="java $EA $z -cp $CP jgi.IdentityMatrix $@"
	echo $CMD >&2
	$CMD
}

idmatrix "$@"
