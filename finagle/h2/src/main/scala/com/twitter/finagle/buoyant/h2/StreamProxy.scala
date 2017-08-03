package com.twitter.finagle.buoyant.h2
import com.twitter.util.{Future, Try}
import scala.collection.mutable

abstract class StreamProxy(underlying: Stream) extends Stream {
  override def isEmpty: Boolean = underlying.isEmpty
  /**
   * Satisfied when an end-of-stream frame has been read from this
   * stream.
   *
   * If the stream is reset prematurely, onEnd fails with a [[Reset]].
   */
  override def onEnd: Future[Unit] = underlying.onEnd
}

/**
 * Wraps an underlying [[Stream]] with an [[StreamOnFrame.onFrame onFrame]] function.
 * The `onFrame` function is called for each frame in the stream.
 *
 * @param underlying the [[Stream]] wrapped by this proxy
 * @param onFrame function called for each [[Frame]] in the underlying [[Stream]]
 */
// TODO: consider renaming `onFrame` to `foreach`?
class StreamOnFrame(underlying: Stream, onFrame: Try[Frame] => Unit) extends StreamProxy(underlying) {
  override def read(): Future[Frame] = underlying.read().respond(onFrame)
  override def toString: String = s"StreamProxy($underlying, onFrame=$onFrame)"
}

/**
 * Wraps an underlying [[Stream]] with a function[[StreamFlatMap.f]] that is called for
 * each frame in the stream.
 *
 * @note that in order to avoid violating flow control, `f` must either release the frame or
 *       return it in the returned sequence of frames
 */
class StreamFlatMap(underlying: Stream, f: Frame => Seq[Frame]) extends StreamProxy(underlying) {
  private[this] var q = new mutable.Queue[Frame]()

  override def read(): Future[Frame] = synchronized {
    if (q.nonEmpty) Future.value(q.dequeue())
    else underlying.read().map(f).map { fs =>
      q.enqueue(fs: _*)
      q.dequeue()
    }
  }

  override def toString: String = s"StreamProxy($underlying, flatMap=$f)"
}