/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.messages

import java.util

import android.content.Context
import android.support.v7.widget.RecyclerView.{OnScrollListener, ViewHolder}
import android.support.v7.widget.{DefaultItemAnimator, LinearLayoutManager, RecyclerView}
import android.util.AttributeSet
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model.{ConvId, MessageData, MessageId}
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.controllers.global.SelectionController
import com.waz.zclient.messages.MessageView.MsgBindOptions
import com.waz.zclient.messages.ScrollController.Scroll
import com.waz.zclient.{Injectable, Injector, ViewHelper}

class MessagesListView(context: Context, attrs: AttributeSet, style: Int) extends RecyclerView(context, attrs, style) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  import MessagesListView._

  val width = Signal[Int]()
  val height = Signal[Int]()
  val layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
    override def supportsPredictiveItemAnimations(): Boolean = true
  }
  val adapter = new MessagesListAdapter(width)
  val scrollController = new ScrollController(adapter, height)

  setHasFixedSize(true)
  setLayoutManager(layoutManager)
  setAdapter(adapter)
  setItemAnimator(new ItemChangeAnimator)

  scrollController.onScroll { case Scroll(pos, smooth) =>
    verbose(s"Scrolling to pos: $pos, smooth: $smooth")
    val scrollTo = math.min(adapter.getItemCount - 1, pos)
    if (smooth) {
      val current = layoutManager.findFirstVisibleItemPosition()
      // jump closer to target position before scrolling, don't want to smooth scroll through many messages
      if (math.abs(current - pos) > MaxSmoothScroll)
        layoutManager.scrollToPosition(if (pos > current) pos - MaxSmoothScroll else pos + MaxSmoothScroll)

      smoothScrollToPosition(pos) //TODO figure out how to provide an offset, we should scroll to top of the message
    } else {
      layoutManager.scrollToPosition(scrollTo)
    }
  }

  addOnScrollListener(new OnScrollListener {
    override def onScrollStateChanged(recyclerView: RecyclerView, newState: Int): Unit = newState match {
      case RecyclerView.SCROLL_STATE_IDLE =>
        scrollController.onScrolled(layoutManager.findLastCompletelyVisibleItemPosition())
      case RecyclerView.SCROLL_STATE_DRAGGING =>
        scrollController.onDragging()
      case _ =>
    }
  })

  override def onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int): Unit = {
    width ! (r - l)
    height ! (b - t)
    super.onLayout(changed, l, t, r, b)
  }

  def scrollToBottom(): Unit = scrollController.onScrollToBottomRequested ! layoutManager.findLastCompletelyVisibleItemPosition()
}

object MessagesListView {

  val MaxSmoothScroll = 50

  case class UnreadIndex(index: Int) extends AnyVal

  abstract class Adapter extends RecyclerView.Adapter[MessageViewHolder] {
    def getConvId: ConvId
    def getUnreadIndex: UnreadIndex
  }
}

case class MessageViewHolder(view: MessageView, adapter: MessagesListAdapter)(implicit ec: EventContext, inj: Injector) extends RecyclerView.ViewHolder(view) with Injectable {

  private val selection = inject[SelectionController].messages
  private val msgsController = inject[MessagesController]
  private var id: MessageId = _
  private var opts = Option.empty[MsgBindOptions]
  private var _isFocused = false

  selection.focused.onChanged.on(Threading.Ui) { f =>
    if (_isFocused != f.exists(_._1 == id)) adapter.notifyItemChanged(getAdapterPosition)
  }

  msgsController.lastSelfMessage.onChanged.on(Threading.Ui) { m =>
    opts foreach { o =>
      if (o.isLastSelf != (m.id == id)) adapter.notifyItemChanged(getAdapterPosition)
    }
  }

  msgsController.lastMessage.onChanged.on(Threading.Ui) { m =>
    opts foreach { o =>
      if (o.isLast != (m.id == id)) adapter.notifyItemChanged(getAdapterPosition)
    }
  }

  def isFocused = _isFocused

  def bind(msg: MessageAndLikes, prev: Option[MessageData], opts: MsgBindOptions): Unit = {
    view.set(msg, prev, opts)
    id = msg.message.id
    this.opts = Some(opts)
    _isFocused = selection.lastFocused.contains(id)

    msgsController.onMessageRead(msg.message)
  }
}
