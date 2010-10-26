/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.naggati

import org.jboss.netty.buffer.ChannelBuffer

object Stages {
  /**
   * Generate a Stage from a code block.
   */
  final def stage(f: ChannelBuffer => NextStep): Stage = new Stage {
    def apply(buffer: ChannelBuffer) = f(buffer)
  }

  /**
   * Wrap a Stage. The wrapped stage will be regenerated on each call.
   */
  final def proxy(stage: => Stage): Stage = new Stage {
    def apply(buffer: ChannelBuffer) = stage.apply(buffer)
  }

  /**
   * Allow a decoder to return a Stage when we expected a NextStep.
   */
  implicit final def stageToNextStep(stage: Stage) = GoToStage(stage)

  final def emit(obj: AnyRef) = Emit(obj)

  /**
   * Ensure that a certain number of bytes is buffered before executing the next step, calling
   * `getCount` each time new data arrives, to recompute the total number of bytes desired.
   */
  final def ensureBytesDynamic(getCount: => Int)(process: ChannelBuffer => NextStep) = proxy {
    ensureBytes(getCount)(process)
  }

  /**
   * Ensure that a certain number of bytes is buffered before executing the * next step.
   */
  final def ensureBytes(count: Int)(process: ChannelBuffer => NextStep) = stage { buffer =>
    if (buffer.readableBytes < count) {
      Incomplete
    } else {
      process(buffer)
    }
  }

  /**
   * Read a certain number of bytes into a byte buffer and pass that buffer to the next step in
   * processing. `getCount` is called each time new data arrives, to recompute * the total number of
   * bytes desired.
   */
  final def readBytesDynamic(getCount: => Int)(process: Array[Byte] => NextStep) = proxy {
    readBytes(getCount)(process)
  }

  /**
   * Read `count` bytes into a byte buffer and pass that buffer to the next step in processing.
   */
  final def readBytes(count: Int)(process: Array[Byte] => NextStep) = stage { buffer =>
    if (buffer.readableBytes < count) {
      Incomplete
    } else {
      val bytes = new Array[Byte](count)
      buffer.readBytes(bytes)
      process(bytes)
    }
  }

  /**
   * Read bytes until a delimiter is present. The number of bytes up to and including the delimiter
   * is passed to the next processing step. `getDelimiter` is called each time new data arrives.
   */
  final def ensureDelimiterDynamic(getDelimiter: => Byte)(process: (Int, ChannelBuffer) => NextStep) = proxy {
    ensureDelimiter(getDelimiter)(process)
  }

  /**
   * Read bytes until a delimiter is present. The number of bytes up to and including the delimiter
   * is passed to the next processing step.
   */
  final def ensureDelimiter(delimiter: Byte)(process: (Int, ChannelBuffer) => NextStep) = stage { buffer =>
    val n = buffer.bytesBefore(delimiter)
    if (n < 0) {
      Incomplete
    } else {
      process(n + 1, buffer)
    }
  }

  /**
   * Read a line, terminated by LF or CR/LF, and pass that line as a string to the next processing
   * step.
   *
   * @param removeLF true if the LF or CRLF should be stripped from the
   *   string before passing it on
   * @param encoding byte-to-character encoding to use
   */
  final def readLine(removeLF: Boolean, encoding: String)(process: String => NextStep) = {
    ensureDelimiter('\n'.toByte) { (n, buffer) =>
      val end = if ((n > 1) && (buffer.getByte(buffer.readerIndex + n - 2) == '\r'.toByte)) {
        n - 2
      } else {
        n - 1
      }
      val byteBuffer = new Array[Byte](n)
      buffer.readBytes(byteBuffer)
      process(new String(byteBuffer, 0, (if (removeLF) end else n), encoding))
    }
  }
}
