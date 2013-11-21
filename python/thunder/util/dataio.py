# utilities for loading and saving data

from scipy.io import savemat
from numpy import array, mean, float16
import pyspark

def parse(data, filter="raw", inds=None) :

	def parseVector(line, filter="raw", inds=None) :

		vec = [float(x) for x in line.split(' ')]
		ts = array(vec[3:]) # get tseries
		if filter == "dff" : # convert to dff
			meanVal = mean(ts)
			ts = (ts - meanVal) / (meanVal + 0.1)
		if filter == "sub" : # convert to dff
			ts = (ts - mean(ts))
		if inds is not None :
			if inds == "xyz" :
				return ((int(vec[0]),int(vec[1]),int(vec[2])),ts)
			if inds == "linear" :
				k = int(vec[0]) + int((vec[1] - 1)*1650)
				return (k,ts)
		else :
			return ts

	return data.map(lambda x : parseVector(x,filter,inds))
	

def saveout(data, outputDir, outputFile, outputFormat) :

	if outputFormat == "matlab" :
		dtype = type(data) 
		if (dtype == pyspark.rdd.RDD) | (dtype == pyspark.rdd.PipelinedRDD) :
			savemat(outputDir+"/"+outputFile+".mat",mdict={outputFile : data.map(float16).collect()},oned_as='column',do_compression='true')
		else :
			savemat(outputDir+"/"+outputFile+".mat",mdict={outputFile : data},oned_as='column',do_compression='true')
