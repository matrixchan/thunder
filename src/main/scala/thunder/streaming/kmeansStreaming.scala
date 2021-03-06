package thunder

import org.apache.spark.streaming._
import org.apache.spark.streaming.StreamingContext._
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Vector
import scala.util.Random.nextDouble
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.Color
import java.io.File
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.Array

object kmeansStreaming {

  def parseVector(line: String, t: Int): (Int, Vector) = {
    val nums = line.split(' ') // split line into numbers: (0) key (1) ca (2) id
    val k = nums(0).toInt // get index as key
    val id = nums(2).toInt - 1
    val vals, counts = Array.fill[Double](t)(0)
    vals(id) = nums(1).toDouble
    counts(id) += 1
    return (k, Vector(vals ++ counts))
  }

  def makeMap(vec: Array[Double]): List[Map[String,Double]] = {
    vec.toList.zipWithIndex.map(x => Map("x"->x._2.toDouble,"y"->x._1))
  }

  def getDffs(vals: (Int, Vector), t: Int): (Int, (Vector,Double)) = {
    val resp = vals._2.elements.slice(0,t)
    val counts = vals._2.elements.slice(t,2*t)
    val baseLine = resp.sum / counts.sum
    val dff = resp.zip(counts).map{
      case (x,y) => if (y == 0) {0} else {x/y}}.map(
      x => if (x == 0) {0} else {(x - baseLine) / (baseLine + 0.1)})
    val out = Vector(dff)
    return (vals._1, (out,std(out)))
  }

  def clip(num: Int): Int = {
    var out = num
    if (num < 0) {
      out = 0
    } else if (num > 255) {
      out = 255
    }
    return out
  }

  def corrToRGB(ind: Int, sig: Double, k: Int): Array[Int] = {
    var out = Array(0,0,0)
    if (sig > 0) {
      val clr = Color.getHSBColor((ind).toFloat / k,(0.7).toFloat,1.toFloat)
      out = Array(clr.getRed(),clr.getGreen(),clr.getBlue())
    }
    else {
      out = Array(0,0,0)
    }

    return out
  }

  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try {
      op(p)
    } finally {
      p.close()
    }
  }

  def printToImage(rdd: RDD[(Int,Double)], k: Int, width: Int, height: Int, fileName: String, thresh: Double): Unit = {
    val nPixels = width * height
    val inds = rdd.map(x => x._1).collect()
    //val base = rdd.map(x => clip(((x._2-1000)/8000 * 255).toInt)).collect()
    val sig = rdd.map(x => if (x._2 > thresh) {1} else {0}).collect()
    val RGB = Array.range(0, nPixels).flatMap(x => corrToRGB(inds(x),sig(x),k))
    val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val raster = img.getRaster()
    raster.setPixels(0, 0, width, height, RGB)
    ImageIO.write(img, "png", new File(fileName+"1.png"))
  }

  def getMeanResp(vals: (Int, Vector), t: Int) : (Int,(Double,Int)) = {
    val resp = vals._2.elements.slice(0,t)
    val counts = vals._2.elements.slice(t,2*t)
    val baseLine = resp.sum / counts.sum
    return (vals._1,(baseLine,counts.sum.toInt))
  }

  def std(p: Vector): Double = {
    val p1 = Vector(p.elements.map(x => x - p.sum / p.length))
    return scala.math.sqrt(p1.dot(p1) / (p.length - 1))
  }

   def corrcoef(p1 : Vector, p2: Vector): Double = {
    val p11 = Vector(p1.elements.map(x => x - p1.sum / p1.length))
    val p22 = Vector(p2.elements.map(x => x - p2.sum / p2.length))
    return (p11 / scala.math.sqrt(p11.dot(p11))).dot(p22 / scala.math.sqrt(p22.dot(p22)))
  }

  def closestPoint(p: Vector, centers: Array[Vector]): Int = {
    var index = 0
    var bestIndex = 0
    var closest = Double.PositiveInfinity

    for (i <- 0 until centers.length) {
      val tempDist = p.squaredDist(centers(i))
      if (tempDist < closest) {
        closest = tempDist
        bestIndex = i
      }
    }
    return bestIndex
  }

  // make this an RDD of Vector and std: Double, then threshold on std before updating centers
  def updateCenters(rdd: RDD[(Vector,Double)], centers: Array[Vector], saveFile: String, thresh: Double): Array[Vector] = {
    val data = rdd.filter{case (x,y) => y > thresh}.map{case (x,y) => x}
    //for (ik <- 0 until centers.size) {
    //  centers(ik) = centers(ik) + Vector(Array.fill(centers(ik).length)((nextDouble-0.5)/10))
    //}
    for (i <- 0 until 10) {
      val closest = data.map (p => (closestPoint(p, centers), (p, 1)))
      val pointStats = closest.reduceByKey{case ((x1, y1), (x2, y2)) => (x1 + x2, y1 + y2)}
      val newPoints = pointStats.map {pair => (pair._1, pair._2._1 / pair._2._2)}.collectAsMap()
      for (newP <- newPoints) {
        centers(newP._1) = newP._2
      }
    }

    print(centers(0))
    print(centers(1))

    var centersForPrinting = List((makeMap(centers(0).elements)))
    for (ik <- 1 until centers.size) {
      centersForPrinting = centersForPrinting ++ List((makeMap(centers(ik).elements)))
    }

    val out = Array(centersForPrinting.toJson.prettyPrint)
    printToFile(new File(saveFile+".json"))(p => {
      out.foreach(p.println)
    })

    return centers
  }

  def main(args: Array[String]) {
    if (args.length < 8) {
      System.err.println("Usage: kmeansOnline <master> <directory> <outputFile> <batchTime> <windowTime> <k> <t> <width> <height> <thresh> <nSlices>")
      System.exit(1)
    }

    // create spark context
    System.setProperty("spark.executor.memory", "120g")
    System.setProperty("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    if (args(7).toInt != 0) {
      System.setProperty("spark.default.parallelism", args(10).toString)
    }
    val saveFile = args(2)
    val batchTime = args(3).toLong
    val windowTime = args(4).toLong
    val k = args(5).toInt
    val t = args(6).toInt
    val width = args(7).toInt
    val height = args(8).toInt
    val thresh = args(9).toDouble

    val ssc = new StreamingContext(args(0), "SimpleStreaming", Seconds(batchTime),
      System.getenv("SPARK_HOME"), List("target/scala-2.9.3/thunder_2.9.3-1.0.jar","project/spray-json_2.9.3-1.2.5.jar"))
    ssc.checkpoint(System.getenv("CHECKPOINTSTREAMING"))

    var centers = new Array[Vector](k)
    for (ik <- 0 until k) {
      centers(ik) = Vector(Array.fill(t)((nextDouble-0.5)/10))
    }

    // main streaming operations
    val lines = ssc.textFileStream(args(1)) // directory to monitor
    val dataStream = lines.map(x => parseVector(x,t)) // parse data
    val meanStream = dataStream.reduceByKeyAndWindow(_ + _, _ - _, Seconds(windowTime), Seconds(batchTime))
    val dffStream = meanStream.map(x => getDffs(x,t)).transform(rdd => rdd.sortByKey(true))
    dffStream.foreach(rdd =>
      centers = updateCenters(rdd.map{case (k,v) => (v._1,v._2)},centers,saveFile,thresh)
    )
    //dffStream.print()

    //val meanRespStream = meanStream.map(x => getMeanResp(x,t)).transform(rdd => rdd.sortByKey(true))
    //meanRespStream.foreach(rdd => printToImage2(rdd.map{case (k,v) => v._1},width,height,saveFile))

    //meanRespStream.print()

    val dists = dffStream.transform(rdd => rdd.map{
      case (k,v) => (closestPoint(v._1,centers),v._2)})

    //dists.print()
    dists.foreach(rdd => printToImage(rdd, k, width, height, saveFile, thresh))

    ssc.start()
  }

}



