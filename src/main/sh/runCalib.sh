#!/bin/bash --login
#SBATCH --time=200:00:00
#SBATCH --output=./logfiles/logfile_%x-%j.log
#SBATCH --partition=smp
#SBATCH --nodes=1
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=16
#SBATCH --mem=46G
#SBATCH --job-name=cal-modeChoice-cdmx
#SBATCH --mail-type=END,FAIL
#SBATCH --mail-user=meinhardt@vsp.tu-berlin.de

date
hostname

if [ ! -d "logfiles" ]; then 
	mkdir logfiles
fi

command="python3.9 -u calibrate.py"

echo ""
echo "command is $command"
echo ""

source env/bin/activate

module add java/17
java -version

$command
