package com.sony.prometheus.annotators

import se.lth.cs.docforia.Document

/** Annotates strings into docforia Documents
 */
trait Annotater {

  /** Returns an annotated docforia Document from input String
    *
    * @param input    the input to annotate into a Document
    * @param lang     the language - defaults to Swedish
    * @param conf     the configuration - defaults to "default"
    * @return        an annotated docforia Document
   */
  def annotate(input: String, lang: String = "sv", conf: String = "default"): Either[String, Document]
}

