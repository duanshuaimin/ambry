package com.github.ambry.router;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Represents a {@link ByteBuffer} as a {@link ReadableStreamChannel}.
 */
public class ByteBufferRSC implements ReadableStreamChannel {
  /**
   * List of "events" (function calls) that can occur inside ByteBufferRSC.
   */
  public static enum Event {
    GetSize,
    Read,
    IsOpen,
    Close
  }

  /**
   * Callback that can be used to listen to events that happen inside ByteBufferRSC.
   * <p/>
   * Please *do not* write tests that check for events *not* arriving. Events will not arrive if there was an exception
   * in the function that triggers the event or inside the function that notifies listeners.
   */
  public interface EventListener {

    /**
     * Called when an event (function call) finishes successfully in ByteBufferRSC. Does *not* trigger if the event
     * (function) fails.
     * @param byteBufferRSC the {@link ByteBufferRSC} where the event occurred.
     * @param event the {@link Event} that occurred.
     */
    public void onEventComplete(ByteBufferRSC byteBufferRSC, Event event);
  }

  private final AtomicBoolean channelOpen = new AtomicBoolean(true);
  private final ReentrantLock bufferReadLock = new ReentrantLock();
  private final ByteBuffer buffer;
  private final List<EventListener> listeners = new ArrayList<EventListener>();

  /**
   * Constructs a {@link ReadableStreamChannel} whose read operations return data from the provided {@code buffer}.
   * @param buffer the {@link ByteBuffer} that is used to retrieve data from on invocation of read operations.
   */
  public ByteBufferRSC(ByteBuffer buffer) {
    this.buffer = buffer;
  }

  @Override
  public long getSize() {
    onEventComplete(Event.GetSize);
    return buffer.capacity();
  }

  @Override
  public int read(WritableByteChannel channel)
      throws IOException {
    int bytesWritten = -1;
    if (!channelOpen.get()) {
      throw new ClosedChannelException();
    } else {
      bufferReadLock.lock();
      try {
        if (buffer.hasRemaining()) {
          bytesWritten = channel.write(buffer);
        }
      } finally {
        bufferReadLock.unlock();
      }
    }
    onEventComplete(Event.Read);
    return bytesWritten;
  }

  @Override
  public boolean isOpen() {
    onEventComplete(Event.IsOpen);
    return channelOpen.get();
  }

  @Override
  public void close()
      throws IOException {
    channelOpen.set(false);
    onEventComplete(Event.Close);
  }

  /**
   * Register to be notified about events that occur in this ByteBufferRSC.
   * @param listener the listener that needs to be notified of events.
   */
  public ByteBufferRSC addListener(EventListener listener) {
    if (listener != null) {
      synchronized (listeners) {
        listeners.add(listener);
      }
    }
    return this;
  }

  /**
   * Notify listeners of events.
   * <p/>
   * Please *do not* write tests that check for events *not* arriving. Events will not arrive if there was an exception
   * in the function that triggers the event or inside this function.
   * @param event the {@link Event} that just occurred.
   */
  private void onEventComplete(Event event) {
    synchronized (listeners) {
      for (EventListener listener : listeners) {
        try {
          listener.onEventComplete(this, event);
        } catch (Exception ee) {
          // too bad.
        }
      }
    }
  }
}
