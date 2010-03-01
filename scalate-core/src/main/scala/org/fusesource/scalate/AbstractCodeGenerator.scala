/*
 * Copyright (c) 2009 Matthew Hildebrand <matt.hildebrand@gmail.com>
 * Copyright (C) 2009, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.fusesource.scalate

import java.util.regex.Pattern
import java.net.URI
import java.io.File

/**
 * Provies a common base class for CodeGenerator implementations.
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
abstract class AbstractCodeGenerator[T] extends CodeGenerator
{
  abstract class AbstractSourceBuilder[T] {
    var indent_level = 0
    var code = ""

    def <<(): this.type = <<("")

    def <<(line: String): this.type = {
      for (i <- 0 until indent_level) {
        code += "  ";
      }
      code += line + "\n";
      this
    }

    def indent[T](op: => T): T = {indent_level += 1; val rc = op; indent_level -= 1; rc}

    def generate(packageName: String, className: String, bindings: List[Binding], statements: List[T]): Unit = {

      this << "/* NOTE this file is autogenerated by Scalate : see http://scalate.fusesource.org/ */"
      if (packageName != "") {
        this << "package " + packageName
      }

      this << ""
      this << "object " + className + "{"
      indent {
        // We prefix the function an variables with $_scalate_$ to avoid namespace pollution which could
        // conflict with definitions declared in the template
        this << "def $_scalate_$render($_scalate_$_context:_root_.org.fusesource.scalate.RenderContext): Unit = {"
        indent {
          generateBindings(bindings) {
            generate(statements)
          }
        }
        this << "}"
      }
      this << "}"
      this <<;


      this <<;
      this << "class " + className + " extends _root_.org.fusesource.scalate.Template {"
      indent {
        this << "def render(context:_root_.org.fusesource.scalate.RenderContext): Unit = " + className + ".$_scalate_$render(context);"
      }
      this << "}"

    }

    def generate(statements: List[T]): Unit


    def generateBindings(bindings: List[Binding])(body: => Unit): Unit = {
      bindings.foreach(arg => {
        generateBinding(arg)
        this << "{"
        indent_level += 1
      })

      body

      bindings.foreach(arg => {
        indent_level -= 1
        this << "}"
      })
    }

    def generateBinding(binding: Binding): Unit = {
      this << binding.kind + " " + binding.name + ":" + binding.className + " = ($_scalate_$_context.attributes.get(" + asString(binding.name) + ") match {"
      indent {
        if (binding.defaultValue.isEmpty) {
          this << "case None => { throw new _root_.org.fusesource.scalate.NoValueSetException(" + asString(binding.name) + ") }"
          this << "case Some(null) => { throw new _root_.org.fusesource.scalate.NoValueSetException(" + asString(binding.name) + ") }"
          this << "case Some(value) => { value.asInstanceOf[" + binding.className + "] }"
        } else {
          this << "case None => { " + binding.defaultValue.get + " }"
          this << "case Some(null) => { " + binding.defaultValue.get + " }"
          this << "case Some(value) => { value.asInstanceOf[" + binding.className + "] }"
        }
      }
      this << "});"
      if (binding.importMembers) {
        this << "import " + binding.name + "._;";
      }
    }

    def asString(text: String): StringBuffer = {
      val buffer = new StringBuffer
      buffer.append("\"")
      text.foreach(c => {
        if (c == '"')
          buffer.append("\\\"")
        else if (c == '\\')
          buffer.append("\\\\")
        else if (c == '\n')
          buffer.append("\\n")
        else if (c == '\r')
          buffer.append("\\r")
        else if (c == '\b')
          buffer.append("\\b")
        else if (c == '\t')
          buffer.append("\\t")
        else if ((c >= '#' && c <= '~') || c == ' ' || c == '!')
          buffer.append(c)
        else {
          buffer.append("\\u")
          buffer.append(format("%04x", c.asInstanceOf[Int]))
        }
      })
      buffer.append("\"")
      buffer
    }
  }

  override def className(uri: String): String = {
    // Determine the package and class name to use for the generated class
    val (packageName, cn) = extractPackageAndClassNames(uri)

    // Build the complete class name (including the package name, if any)
    if (packageName == null || packageName.length == 0)
      cn
    else
      packageName + "." + cn
  }

  protected def extractPackageAndClassNames(uri: String): (String, String) = {
    val normalizedURI = try {
      new URI(uri).normalize
    } catch {
      // on windows we can't create a URI from files named things like C:/Foo/bar.ssp
      case e: Exception => val name = new File(uri).getCanonicalPath
      val sep = File.pathSeparator
      if (sep != "/") {
        // on windows lets replace the \ in a directory name with /
        val newName = name.replace(File.pathSeparatorChar, '/')
        println("convertedd windows path into: " + newName)
        newName
      }
      else {
        name
      }
    }
    val SPLIT_ON_LAST_SLASH_REGEX = Pattern.compile("^(.*)/([^/]*)$")
    val matcher = SPLIT_ON_LAST_SLASH_REGEX.matcher(normalizedURI.toString)
    if (matcher.matches == false) throw new TemplateException("Internal error: unparseable URI [" + uri + "]")
    val unsafePackageName = matcher.group(1).replaceAll("[^A-Za-z0-9_/]", "_").replaceAll("/", ".").replaceFirst("^\\.", "")
    val packages = unsafePackageName.split("\\.")
    val packageName = packages.map(safePackageName(_)).mkString(".")

    val cn = "$_scalate_$" + matcher.group(2).replace('.', '_')
    (packageName, cn)
  }

  /**
   * Lets avoid any dodgy named packages based on compiling a file in a strange directory (such as a temporary file)
   */
  private def safePackageName(name: String): String = if (reservedWords.contains(name)) {
      "_" + name
    }
    else if (!name.isEmpty && name(0).isDigit) {
      "_" + name
    }
    else {
      name
    }

  protected val reservedWords = Set[String]("package", "class", "trait", "if", "else", "while", "def", "extends", "val" , "var")
}
