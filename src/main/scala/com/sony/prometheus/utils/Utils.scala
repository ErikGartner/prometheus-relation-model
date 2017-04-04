package com.sony.prometheus.utils


import java.nio.file.{Files, Paths}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.SparkContext

/**
  * Created by axel on 2017-03-23.
  */
object Utils {
  object Colours {
    final val RESET: String = "\u001B[0m"
    final val BOLD: String = "\u001B[1m"
    final val RED: String = "\u001B[31m"
    final val GREEN: String = "\u001B[32m"
  }


  /** Returns true if path (file or dir) exists
    * @param path - the path to check, hdfs or local
    * @return     - true if path exists
    */
  def pathExists(path: String)(implicit sc: SparkContext): Boolean = {
    if (path.split(":")(0) == "hdfs") {
      val conf = sc.hadoopConfiguration
      System.getProperty("HDFS_ADDRESS")
      conf.set("fs.default.name", "hdfs://semantica004.cs.lth.se:8020")
      val fs = org.apache.hadoop.fs.FileSystem.get(sc.hadoopConfiguration)
      fs.exists(new Path(path.split(":")(1)))
    } else {
      Files.exists(Paths.get(path))
    }
  }
}
