/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Freetalk;

import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import plugins.Freetalk.Message.Attachment;
import plugins.Freetalk.MessageList.MessageReference;
import plugins.Freetalk.exceptions.DuplicateBoardException;
import plugins.Freetalk.exceptions.DuplicateMessageException;
import plugins.Freetalk.exceptions.DuplicateMessageListException;
import plugins.Freetalk.exceptions.InvalidParameterException;
import plugins.Freetalk.exceptions.NoSuchBoardException;
import plugins.Freetalk.exceptions.NoSuchIdentityException;
import plugins.Freetalk.exceptions.NoSuchMessageException;
import plugins.Freetalk.exceptions.NoSuchMessageListException;

import com.db4o.ObjectSet;
import com.db4o.ext.ExtObjectContainer;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Logger;

/**
 * The MessageManager is the core connection between the UI and the backend of the plugin:
 * It is the entry point for posting messages, obtaining messages, obtaining boards, etc.
 * 
 * 
 * @author xor (xor@freenetproject.org)
 */
public abstract class MessageManager implements Runnable {

	protected final IdentityManager mIdentityManager;
	
	protected final Freetalk mFreetalk;
	
	protected final ExtObjectContainer db;
	
	protected final PluginRespirator mPluginRespirator;
	

	/* FIXME: This really has to be tweaked before release. I set it quite short for debugging */
	
	private static final int STARTUP_DELAY = 3 * 60 * 1000;
	private static final int THREAD_PERIOD = 15 * 60 * 1000;
	
	private volatile boolean isRunning = false;
	private volatile boolean shutdownFinished = false;
	private Thread mThread;

	public MessageManager(ExtObjectContainer myDB, IdentityManager myIdentityManager, Freetalk myFreetalk, PluginRespirator myPluginRespirator) {
		assert(myDB != null);
		assert(myIdentityManager != null);
		assert(myFreetalk != null);
		assert(myPluginRespirator != null);		
		
		db = myDB;
		mIdentityManager = myIdentityManager;
		mFreetalk = myFreetalk;
		mPluginRespirator = myPluginRespirator;
		
		// It might happen that Freetalk is shutdown after a message has been downloaded and before addMessagesToBoards was called:
		// Then the message will still be stored but not visible in the boards because storing a message and adding it to boards are separate transactions.
		// Therefore, we must call addMessagesToBoards during startup.
		addMessagesToBoards();
	}
	
	/**
	 * For being used in JUnit tests to run without a node.
	 */
	protected MessageManager(ExtObjectContainer myDB, IdentityManager myIdentityManager) {
		assert(myDB != null);
		assert(myIdentityManager != null);
		
		db = myDB;
		mIdentityManager = myIdentityManager;
		mFreetalk = null;
		mPluginRespirator = null;
	}
	
	public void run() {
		Logger.debug(this, "Message manager started.");
		mThread = Thread.currentThread();
		isRunning = true;
		
		Random random = mPluginRespirator.getNode().fastWeakRandom;
		
		try {
			Logger.debug(this, "Waiting for the node to start up...");
			Thread.sleep(STARTUP_DELAY/2 + random.nextInt(STARTUP_DELAY));
		}
		catch (InterruptedException e)
		{
			mThread.interrupt();
		}
		
		try {
			while(isRunning) {
				Logger.debug(this, "Message manager loop running...");
				
				Logger.debug(this, "Message manager loop finished.");

				try {
					Thread.sleep(THREAD_PERIOD/2 + random.nextInt(THREAD_PERIOD));  // TODO: Maybe use a Ticker implementation instead?
				}
				catch (InterruptedException e)
				{
					mThread.interrupt();
					Logger.debug(this, "Message manager loop interrupted!");
				}
			}
		}
		
		finally {
			synchronized (this) {
				shutdownFinished = true;
				Logger.debug(this, "Message manager thread exiting.");
				notify();
			}
		}
	}

	public void terminate() {
		Logger.debug(this, "Stopping the message manager..."); 
		isRunning = false;
		mThread.interrupt();
		synchronized(this) {
			while(!shutdownFinished) {
				try {
					wait();
				}
				catch (InterruptedException e) {
					Thread.interrupted();
				}
			}
		}
		Logger.debug(this, "Stopped the message manager.");
	}
	
	/**
	 * This is the primary function for posting messages.
	 * 
	 * @param myParentMessage The message to which the new message is a reply. Null if the message should be a thread.
	 * @param myBoards The boards to which the new message should be posted. Has to contain at least one board.
	 * @param myReplyToBoard The board to which replies to this message should be sent. This is just a recommendation. Notice that it should be contained in myBoards. Can be null.
	 * @param myAuthor The author of the new message. Cannot be null.
	 * @param myTitle The subject of the new message. Cannot be null or empty.
	 * @param myDate The UTC time of the message. Null to use the current time.
	 * @param myText The body of the new message. Cannot be null.
	 * @param myAttachments The Attachments of the new Message. See <code>Message.Attachment</code>. Set to null if the message has none.
	 * @return The new message.
	 * @throws InvalidParameterException Invalid board names, invalid title, invalid body.
	 * @throws Exception 
	 */
	public abstract OwnMessage postMessage(MessageURI myParentThreadURI, Message myParentMessage, Set<Board> myBoards, Board myReplyToBoard, FTOwnIdentity myAuthor,
			String myTitle, Date myDate, String myText, List<Attachment> myAttachments) throws InvalidParameterException, Exception;

	public OwnMessage postMessage(MessageURI myParentThreadURI, Message myParentMessage, Set<String> myBoards, String myReplyToBoard,
			FTOwnIdentity myAuthor, String myTitle, Date myDate, String myText, List<Attachment> myAttachments) throws Exception {

		HashSet<Board> boardSet = new HashSet<Board>();
		for (Iterator<String> i = myBoards.iterator(); i.hasNext(); ) {
			String boardName = i.next();
			Board board = getOrCreateBoard(boardName);
			boardSet.add(board);
		}

		Board replyToBoard = null;
		if (myReplyToBoard != null) {
			replyToBoard = getOrCreateBoard(myReplyToBoard);
		}

		return postMessage(myParentThreadURI, myParentMessage, boardSet, replyToBoard, myAuthor, myTitle, myDate, myText, myAttachments);
	}
	
	@SuppressWarnings("unchecked")
	public synchronized int countUnsentMessages() {
		Query q = db.query();
		q.constrain(OwnMessage.class);
		q.descend("mRealURI").constrain(null).identity();
		int unsentCount = q.execute().size();
		
		q = db.query();
		q.constrain(OwnMessageList.class);
		q.descend("iWasInserted").constrain(false);
		ObjectSet<OwnMessageList> notInsertedLists = q.execute();
		for(OwnMessageList list : notInsertedLists)
			unsentCount += list.getMessageCount();
		
		return unsentCount;
	}
	
	/**
	 * Called by the {@link IdentityManager} before an identity is deleted from the database.
	 * 
	 * Deletes any messages and message lists referencing to it and commits the transaction.
	 */
	public synchronized void onIdentityDeletion(FTIdentity identity) {
		synchronized(db.lock()) {
			try {
				for(Message message : getMessagesBy(identity)) {
					message.initializeTransient(db, this);

					for(Board board : message.getBoards()) {
						Iterator<SubscribedBoard> iter = subscribedBoardIterator(board.getName());
						while(iter.hasNext()){
							SubscribedBoard subscribedBoard = iter.next();
							
							try {
								subscribedBoard.deleteMessage(message);
							} catch (NoSuchMessageException e) {
							}
						}
					}

					message.deleteWithoutCommit();
				}

				for(MessageList messageList : getMessageListsBy(identity)) {
					messageList.initializeTransient(db, this);
					messageList.deleteWithoutCommit();
				}

				if(identity instanceof FTOwnIdentity) {
					for(OwnMessage message : getOwnMessagesBy((FTOwnIdentity)identity)) {
						message.initializeTransient(db, this);
						message.deleteWithoutCommit();
					}

					for(OwnMessageList messageList : getOwnMessageListsBy((FTOwnIdentity)identity)) {
						messageList.initializeTransient(db, this);
						messageList.deleteWithoutCommit();
					}
					
					Iterator<SubscribedBoard> iter = subscribedBoardIterator((FTOwnIdentity)identity);
					while(iter.hasNext()) {
						SubscribedBoard board = iter.next();
						board.deleteWithoutCommit();
					}
				}
				db.commit(); Logger.debug(this, "COMMITED: Messages and message lists deleted for " + identity);
			}
			catch(RuntimeException e) {
				DBUtil.rollbackAndThrow(db, this, e);
			}
		}
	}
	
	/**
	 * Called by the {@link MessageListInserter} implementation when the insertion of an {@link OwnMessageList} is to be started.
	 * Has to be called before any data is pulled from the {@link OwnMessageList}: It locks the list so no further messages can be added.
	 * Further, you have to acquire the lock on this MessageManager before calling this function and while taking data from the {@link OwnMessageList} since
	 * the lock of the message list could be cleared and further messages could be added if you do not.
	 * 
	 * @param uri The URI of the {@link OwnMessageList}.
	 * @throws NoSuchMessageListException If there is no such {@link OwnMessageList}.
	 */
	public synchronized void onMessageListInsertStarted(OwnMessageList list) {
		synchronized(db.lock()) {
			try {
				list.beginOfInsert();
				db.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				db.rollback();
				Logger.error(this, "ROLLED BACK: Exception in onMessageListInsertStarted for " + list, e);
				// This function MUST NOT succeed if the list was not marked as being inserted: Otherwise messages could be added to the list while it is
				// being inserted already, resulting in the messages being marked as successfully inserted but not being visible to anyone!
				throw e;
			}
		}
	}
	
	/**
	 * Called by the {@link MessageListInserter} implementation when the insertion of an {@link OwnMessageList} succeeded. Marks the list as inserted.
	 * 
	 * @param uri The URI of the {@link OwnMessageList}.
	 * @throws NoSuchMessageListException If there is no such {@link OwnMessageList}.
	 */
	public synchronized void onMessageListInsertSucceeded(FreenetURI uri) throws NoSuchMessageListException {
		synchronized(db.lock()) {
			try {
				OwnMessageList list = getOwnMessageList(MessageList.getIDFromURI(uri));
				list.markAsInserted();
				db.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException e) {
				db.rollback();
				Logger.error(this, "ROLLED BACK: Exception in onMessageListInsertSucceeded for " + uri, e);
			}
		}
	}
	
	/**
	 * Called by the {@link MessageListInserter} implementation when the insertion of an {@link OwnMessageList} fails.
	 * Clears the "being inserted"-flag of the given message list.
	 * 
	 * @param uri The URI of the {@link OwnMessageList}.
	 * @param collision Whether the index of the {@link OwnMessageList} was already taken. If true, the index of the message list is incremented.
	 * @throws NoSuchMessageListException If there is no such {@link OwnMessageList}.
	 */
	public abstract void onMessageListInsertFailed(FreenetURI uri, boolean collision) throws NoSuchMessageListException;
	
	public synchronized void onMessageReceived(Message message) {
		try {
			get(message.getID());
			Logger.debug(this, "Downloaded a message which we already have: " + message.getURI());
		}
		catch(NoSuchMessageException e) {
			
			synchronized(db.lock()) {
				try {
					message.initializeTransient(db, this);
					message.storeWithoutCommit();

					for(MessageReference ref : getAllReferencesToMessage(message.getID()))
						ref.setMessageWasDownloadedFlag();

					db.commit(); Logger.debug(this, "COMMITED.");
				}
				catch(Exception ex) {
					db.rollback(); Logger.error(this, "ROLLED BACK!", ex);
				}
			}
			
			addMessagesToBoards();
		}
	}
	
	@SuppressWarnings("unchecked")
	private synchronized void addMessagesToBoards() {
		Query q = db.query();
		q.constrain(Message.class);
		q.descend("mWasLinkedIn").constrain(false);
		q.constrain(OwnMessage.class).not();
		ObjectSet<Message> invisibleMessages = q.execute();
		
		for(Message message : invisibleMessages) {
			message.initializeTransient(db, this);
			
			boolean allSuccessful = true;
			
			for(Board board : message.getBoards()) {
				synchronized(board) {
				synchronized(message) {
				synchronized(db.lock()) {
					try {
						board.addMessage(message);
						db.commit(); Logger.debug(this, "COMMITED.");
					}
					catch(RuntimeException e) {
						allSuccessful = false;
						db.rollback(); Logger.error(this, "ROLLED BACK: Adding message to board failed", e);
					}
				}
				}
				}

			}
			
			if(allSuccessful) {
				synchronized(message) {
				synchronized(db.lock()) {
					message.setLinkedIn(true);
					message.storeAndCommit();
				}
				}
			}
			
			// FIXME XXX: Now the messages are stored in the Boards but not in the subscribed boards. Next step is to push them to the subscribed boards.
		}
	}
	
	public synchronized void onMessageListReceived(MessageList list) {
		try {
			getMessageList(list.getID());
			Logger.debug(this, "Downloaded a MessageList which we already have: " + list.getURI());
		}
		catch(NoSuchMessageListException e) {
			synchronized(db.lock()) {
				try {
					list.initializeTransient(db, this);
					list.storeWithoutCommit();
					db.commit(); Logger.debug(this, "COMMITED.");
				}
				catch(RuntimeException ex) {
					db.rollback(); Logger.error(this, "ROLLED BACK!", ex);
				}
			}
		}
	}
	
	/**
	 * Abstract because we need to store an object of a child class of MessageList which is chosen dependent on which implementation of the
	 * messging system we are using.
	 */
	public abstract void onMessageListFetchFailed(FTIdentity author, FreenetURI uri, MessageList.MessageListFetchFailedReference.Reason reason);
	
	public synchronized void onMessageFetchFailed(MessageReference messageReference, MessageList.MessageFetchFailedReference.Reason reason) {
		if(reason == MessageList.MessageFetchFailedReference.Reason.DataNotFound) {
			/* FIXME: Messages with DNF should be marked as failed, and the failed mark should be deleted after a few days. This has to be implemented
			 * before the official Freetalk release because otherwise someone could spam the message manager queue with unfetchable messages - preventing the
			 * fetching of any other messages. */
			return;
		}

		try {
			get(messageReference.getMessageID());
			Logger.debug(this, "Trying to mark a message as 'downlod failed' which we actually have: " + messageReference.getURI());
		}
		catch(NoSuchMessageException e) {
			synchronized(db.lock()) {
			try {
				MessageList.MessageFetchFailedReference failedMarker = new MessageList.MessageFetchFailedReference(messageReference, reason);
				failedMarker.initializeTransient(db);
				failedMarker.storeWithoutCommit();
				
				for(MessageReference r : getAllReferencesToMessage(messageReference.getMessageID()))
					r.setMessageWasDownloadedFlag();
				
				Logger.debug(this, "Marked message as download failed with reason " + reason + ": " +  messageReference.getURI());
				db.commit(); Logger.debug(this, "COMMITED.");
			}
			catch(RuntimeException ex) {
				db.rollback();
				Logger.error(this, "ROLLED BACK: Exception while marking a not-downloadable messge", ex);
			}
			}
		}
	}
	
	/**
	 * Get a list of all MessageReference objects to the given message ID. References to OwnMessage are not returned.
	 * Used to mark the references to a message which was downloaded as downloaded.
	 */
	private Iterable<MessageList.MessageReference> getAllReferencesToMessage(final String id) {
		return new Iterable<MessageList.MessageReference>() {
			@SuppressWarnings("unchecked")
			public Iterator<MessageList.MessageReference> iterator() {
				return new Iterator<MessageList.MessageReference>() {
					private Iterator<MessageList.MessageReference> iter;

					{
						Query query = db.query();
						query.constrain(MessageList.MessageReference.class);
						query.constrain(OwnMessageList.OwnMessageReference.class).not();
						query.descend("mMessageID").constrain(id);
						iter = query.execute().iterator();
					}

					public boolean hasNext() {
						return iter.hasNext();
					}

					public MessageList.MessageReference next() {
						MessageList.MessageReference next = iter.next();
						next.initializeTransient(db);
						return next;
					}

					public void remove() {
						throw new UnsupportedOperationException();
					}
				};
			}
		};
	}

	/**
	 * Get a message by its URI. The transient fields of the returned message will be initialized already.
	 * This will NOT return OwnMessage objects. Your own messages will be returned by this function as soon as they have been downloaded.
	 * @throws NoSuchMessageException 
	 */
	public Message get(FreenetURI uri) throws NoSuchMessageException {
		/* return get(Message.getIDFromURI(uri)); */
		throw new UnsupportedOperationException("Getting a message by it's URI is inefficient compared to getting by ID. Please only repair this function if absolutely unavoidable.");
	}
	
	/**
	 * Get a message by its ID. The transient fields of the returned message will be initialized already.
	 * This will NOT return OwnMessage objects. Your own messages will be returned by this function as soon as they have been downloaded as
	 * if they were normal messages of someone else.
	 * @throws NoSuchMessageException 
	 */
	@SuppressWarnings("unchecked")
	public synchronized Message get(String id) throws NoSuchMessageException {
		Query query = db.query();
		query.constrain(Message.class);
		query.constrain(OwnMessage.class).not();
		query.descend("mID").constrain(id);
		ObjectSet<Message> result = query.execute();

		switch(result.size()) {
			case 1:
				Message m = result.next();
				m.initializeTransient(db, this);
				return m;
			case 0:
				throw new NoSuchMessageException(id);
			default:
				throw new DuplicateMessageException(id);
		}
	}
	
	/**
	 * Get a <code>MessageList</code> by its ID. The transient fields of the returned <code>MessageList</code>  will be initialized already.
	 * This will NOT return <code>OwnMessageList</code> objects. Your own message lists will be returned by this function as soon as they have
	 * been downloaded as if they were normal message  lists of someone else.
	 * @throws NoSuchMessageListException 
	 */
	@SuppressWarnings("unchecked")
	public synchronized MessageList getMessageList(String id) throws NoSuchMessageListException {
		Query query = db.query();
		query.constrain(MessageList.class);
		query.constrain(OwnMessageList.class).not();
		query.descend("mID").constrain(id);
		ObjectSet<MessageList> result = query.execute();

		switch(result.size()) {
			case 1:
				MessageList list = result.next();
				list.initializeTransient(db, this);
				return list;
			case 0:
				throw new NoSuchMessageListException(id);
			default:
				throw new DuplicateMessageListException(id);
		}
	}
	
	@SuppressWarnings("unchecked")
	public synchronized OwnMessageList getOwnMessageList(String id) throws NoSuchMessageListException {
		Query query = db.query();
		query.constrain(OwnMessageList.class);
		query.descend("mID").constrain(id);
		ObjectSet<OwnMessageList> result = query.execute();

		switch(result.size()) {
			case 1:
				OwnMessageList list = result.next();
				list.initializeTransient(db, this);
				return list;
			case 0:
				throw new NoSuchMessageListException(id);
			default:
				throw new DuplicateMessageListException(id);
		}
	}
	
	public OwnMessage getOwnMessage(FreenetURI uri) throws NoSuchMessageException {
		/* return getOwnMessage(Message.getIDFromURI(uri)); */
		throw new UnsupportedOperationException("Getting a message by it's URI is inefficient compared to getting by ID. Please only repair this function if absolutely unavoidable.");
	}
	
	@SuppressWarnings("unchecked")
	public synchronized OwnMessage getOwnMessage(String id) throws NoSuchMessageException {
		Query query = db.query();
		query.constrain(OwnMessage.class);
		query.descend("mID").constrain(id);
		ObjectSet<OwnMessage> result = query.execute();

		switch(result.size()) {
			case 1:
				OwnMessage m = result.next();
				m.initializeTransient(db, this);
				return m;
			case 0:
				throw new NoSuchMessageException(id);
			default:
				throw new DuplicateMessageException(id);
		}
	}

	/**
	 * Get a board by its name. The transient fields of the returned board will be initialized already.
	 * @throws NoSuchBoardException 
	 */
	@SuppressWarnings("unchecked")
	public synchronized Board getBoardByName(String name) throws NoSuchBoardException {
		name = name.toLowerCase();
		
		Query query = db.query();
		query.constrain(Board.class);
		query.constrain(SubscribedBoard.class).not();
		query.descend("mName").constrain(name);
		ObjectSet<Board> result = query.execute();

		switch(result.size()) {
			case 1:
				Board b = result.next();
				b.initializeTransient(db, this);
				return b;
			case 0:
				throw new NoSuchBoardException(name);
			default:
				throw new DuplicateBoardException(name);
		}
	}
	
	/**
	 * Gets the board with the given name. If it does not exist, it is created and stored, the transaction is commited.
	 * @param The name of the desired board
	 * @throws InvalidParameterException If the name is invalid.
	 */
	public synchronized Board getOrCreateBoard(String name) throws InvalidParameterException {
		name = name.toLowerCase();
		
		Board board;

		try {		
			board = getBoardByName(name);
		}
		catch(NoSuchBoardException e) {
			synchronized(db.lock()) {
			try {
				board = new Board(name);
				board.initializeTransient(db, this);
				board.storeWithoutCommit();
				db.commit(); Logger.debug(this, "COMMITED: Created board " + name);
			}
			catch(RuntimeException ex) {
				db.rollback();
				Logger.error(this, "ROLLED BACK: Exception while creating board " + name, ex);
				throw ex;
			}
			}
		}
		
		return board;
	}
	
	/**
	 * For a database Query of result type <code>ObjectSet\<Board\></code>, this function provides an iterator. The iterator of the ObjectSet
	 * cannot be used instead because it will not call initializeTransient() on the boards. The iterator which is returned by this function
	 * takes care of that.
	 * Please synchronize on the <code>MessageManager</code> when using this function, it is not synchronized itself.
	 */
	@SuppressWarnings("unchecked")
	protected Iterator<Board> generalBoardIterator(final Query q) {
		return new Iterator<Board>() {
			private final Iterator<Board> iter = q.execute().iterator();
			
			public boolean hasNext() {
				return iter.hasNext();
			}

			public Board next() {
				final Board next = iter.next();
				next.initializeTransient(db, MessageManager.this);
				return next;
			}

			public void remove() {
				throw new UnsupportedOperationException("Boards cannot be deleted here.");
			}
			
		};
	}
	
	@SuppressWarnings("unchecked")
	protected Iterator<SubscribedBoard> generalSubscribedBoardIterator(final Query q) {
		return new Iterator<SubscribedBoard>() {
			private final Iterator<SubscribedBoard> iter = q.execute().iterator();
			
			public boolean hasNext() {
				return iter.hasNext();
			}

			public SubscribedBoard next() {
				final SubscribedBoard next = iter.next();
				next.initializeTransient(db, MessageManager.this);
				return next;
			}

			public void remove() {
				throw new UnsupportedOperationException("Boards cannot be deleted here.");
			}
			
		};
	}

	/**
	 * Get an iterator of all boards. The transient fields of the returned boards will be initialized already.
	 */
	public synchronized Iterator<Board> boardIterator() {
		Query query = db.query();
		query.constrain(Board.class);
		query.constrain(SubscribedBoard.class).not();
		query.descend("mName").orderDescending();
		return generalBoardIterator(query);
	}
	
	/**
	 * Get all boards which are being subscribed to by at least one {@link FTOwnIdentity}, i.e. the boards from which we should download messages.
	 */
	public synchronized Iterator<Board> boardWithSubscriptionsIterator() {
		Query query = db.query();
		query.constrain(Board.class);
		query.descend("mHasSubscriptions").constrain(true);
		return generalBoardIterator(query);
	}
	
	/**
	 * Get an iterator of boards which were first seen after the given Date, sorted ascending by the date they were first seen at.
	 */
	public synchronized Iterator<SubscribedBoard> subscribedBoardIteratorSortedByDate(FTOwnIdentity subscriber, final Date seenAfter) {
		Query query = db.query();
		query.constrain(SubscribedBoard.class);
		query.descend("mFirstSeenDate").constrain(seenAfter).greater();
		query.descend("mFirstSeenDate").orderAscending();
		return generalSubscribedBoardIterator(query);
	}
	
	public synchronized Iterator<SubscribedBoard> subscribedBoardIterator(FTOwnIdentity subscriber) {
		Query query = db.query();
		query.constrain(SubscribedBoard.class);
		query.descend("mSubscriber").constrain(subscriber).identity();
		query.descend("mName").orderDescending();
		return generalSubscribedBoardIterator(query);
	}
	
	private Iterator<Board> boardAndSubscribedBoardIterator(String boardName) {
    	Query q = db.query();
    	q.constrain(Board.class);
    	q.descend("mName").constrain(boardName);
    	return generalBoardIterator(q);
	}
	
	private Iterator<SubscribedBoard> subscribedBoardIterator(String boardName) {
    	Query q = db.query();
    	q.constrain(SubscribedBoard.class);
    	q.descend("mName").constrain(boardName);
    	return generalSubscribedBoardIterator(q);
    }
	
    @SuppressWarnings("unchecked")
	public synchronized SubscribedBoard getSubscription(FTOwnIdentity subscriber, String boardName) throws NoSuchBoardException {
    	Query q = db.query();
    	q.constrain(SubscribedBoard.class);
    	q.descend("mName").constrain(boardName);
    	q.descend("mSubscriber").constrain(subscriber).identity();
    	ObjectSet<SubscribedBoard> result = q.execute();
    	
    	switch(result.size()) {
    		case 1:
    			SubscribedBoard board = result.next();
    			board.initializeTransient(db, this);
    			return board;
    		case 0: throw new NoSuchBoardException(boardName);
    		default: throw new DuplicateBoardException(boardName);
    	}
    }
    
	
	public SubscribedBoard subscribeToBoard(FTOwnIdentity subscriber, String boardName) throws InvalidParameterException, NoSuchIdentityException {
		synchronized(mIdentityManager) {
			subscriber = mIdentityManager.getOwnIdentity(subscriber.getID()); // Ensure that the identity still exists so the caller does not have to synchronize.

			synchronized(this) {
				Board board = getOrCreateBoard(boardName);

				try {
					return getSubscription(subscriber, boardName);
				}
				catch(NoSuchBoardException e) {
					synchronized(db.lock()) {
						try {
							SubscribedBoard subscribedBoard = new SubscribedBoard(boardName, subscriber);
							subscribedBoard.initializeTransient(db, this);
							subscribedBoard.storeWithoutCommit();
							
							if(board.hasSubscriptions() == false) {
								Logger.debug(this, "First subscription received for board " + board + ", setting it's HasSubscriptions flag.");
								board.setHasSubscriptions(true);
								board.storeWithoutCommit();
							}
							
							db.commit(); Logger.debug(this, "COMMITED.");

							return subscribedBoard;
						}
						catch(RuntimeException error) {
							db.rollback(); Logger.error(this, "subscribeToBoard failed", error);
							throw error;
						}
						catch(InvalidParameterException error) {
							db.rollback(); Logger.error(this, "subscribeToBoard failed", error);
							throw error;							
						}
					}
				}
			}
		}
	}
	
	/**
	 * You have to synchronize on the IdentityManager which was used to obtain the subscriber while calling this function. 
	 */
	public synchronized void unsubscribeFromBoard(FTOwnIdentity subscriber, String boardName) throws NoSuchBoardException {
		SubscribedBoard subscribedBoard = getSubscription(subscriber, boardName);
		
		synchronized(db.lock()) {
			try {
				subscribedBoard.deleteWithoutCommit();
				
				if(subscribedBoardIterator(subscribedBoard.getName()).hasNext() == false) {
					Board board = getBoardByName(subscribedBoard.getName());
					Logger.debug(this, "Last subscription to board " + board + " removed, clearing it's HasSubscriptions flag.");
					board.setHasSubscriptions(false);
					board.storeWithoutCommit();
				}
				
				db.commit(); Logger.debug(this, "COMMITED");
			}
			catch(RuntimeException e) {
				DBUtil.rollbackAndThrow(db, this, e);
			}
		}
	}
	
	/**
	 * Get the next index of which a message from the selected identity is not stored.
	 */
//	public int getUnavailableMessageIndex(FTIdentity messageAuthor) {
//		Query q = db.query();
//		q.constrain(Message.class);
//		q.constrain(OwnMessage.class).not(); /* We also download our own message. This helps the user to spot problems: If he does not see his own messages we can hope that he reports a bug */
//		q.descend("mAuthor").constrain(messageAuthor);
//		q.descend("mIndex").orderDescending(); /* FIXME: Write a native db4o query which just looks for the maximum! */
//		ObjectSet<Message> result = q.execute();
//		
//		return result.size() > 0 ? result.next().getIndex()+1 : 0;
//	}
	
	@SuppressWarnings("unchecked")
	public synchronized Iterator<OwnMessage> notInsertedMessageIterator() {
		return new Iterator<OwnMessage>() {
			private Iterator<OwnMessage> iter;

			{
				Query query = db.query();
				query.constrain(OwnMessage.class);
				query.descend("mRealURI").constrain(null).identity();
				iter = query.execute().iterator();
			}
			
			public boolean hasNext() {
				return iter.hasNext();
			}

			public OwnMessage next() {
				OwnMessage next = iter.next();
				next.initializeTransient(db, MessageManager.this);
				return next;
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}
	
	/**
	 * Get a list of not downloaded messages. This function only returns messages which are posted to a board which an OwnIdentity wants to
	 * receive messages from. However, it might also return messages which are from an author which nobody wants to receive messages from.
	 * Filtering out unwanted authors is done at MessageList-level: MessageLists are only downloaded from identities which we want to read
	 * messages from.
	 */
	@SuppressWarnings("unchecked")
	public synchronized Iterator<MessageList.MessageReference> notDownloadedMessageIterator() {
		return new Iterator<MessageList.MessageReference>() {
			private Iterator<MessageList.MessageReference> iter;

			{
				// TODO: This query is very slow!
				Query query = db.query();
				query.constrain(MessageList.MessageReference.class);
				query.constrain(OwnMessageList.OwnMessageReference.class).not();
				query.descend("mBoard").descend("mHasSubscriptions").constrain(true);
				query.descend("iWasDownloaded").constrain(false);
				/* FIXME: Order the message references randomly with some trick. */
				
				iter = query.execute().iterator();
			}

			public boolean hasNext() {
				return iter.hasNext();
			}

			public MessageList.MessageReference next() {
				MessageList.MessageReference next = iter.next();
				next.initializeTransient(db);
				return next;
			}

			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}


	/**
	 * Get a list of all message lists from the given identity.
	 * If the identity is an {@link FTOwnIdentity}, it's own message lists are only returned if they have been downloaded as normal message lists.
	 * Technically, this means that no objects of class {@link OwnMessageList} are returned.
	 * 
	 * The purpose of this behavior is to ensure that own messages are only displayed to the user if they have been successfully inserted.
	 * 
	 * @param author An identity or own identity.
	 * @return All message lists of the given identity except those of class OwnMessageList.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized List<MessageList> getMessageListsBy(FTIdentity author) {
		Query query = db.query();
		query.constrain(MessageList.class);
		query.constrain(OwnMessageList.class).not();
		query.descend("mAuthor").constrain(author).identity();
		return query.execute();
	}
	
	/**
	 * Get a list of locally stored own message lists of the given identity. 
	 * Locally stored means that only message lists of class {@link OwnMessageList} are returned.
	 * 
	 * This means that there is no guarantee that the returned message lists have actually been inserted to Freenet.
	 * - The message lists returned by this function can be considered as the outbox of the given identity.
	 * 
	 * If you want a list of message lists  which is actually downloadable from Freenet, see {@link getMessageListsBy}.
	 * 
	 * @param author The author of the message lists.
	 * @return All own message lists of the given own identity.
	 */
	@SuppressWarnings("unchecked")
	protected synchronized List<OwnMessageList> getOwnMessageListsBy(FTOwnIdentity author) {
		Query query = db.query();
		query.constrain(OwnMessageList.class);
		query.descend("mAuthor").constrain(author).identity();
		return query.execute();
	}
	
	
	/**
	 * Get a list of all messages from the given identity.
	 * If the identity is an {@link FTOwnIdentity}, it's own messages are only returned if they have been downloaded as normal messages.
	 * Technically, this means that no objects of class {@link OwnMessage} are returned.
	 * 
	 * The purpose of this behavior is to ensure that own messages are only displayed to the user if they have been successfully inserted.
	 * 
	 * Does not lock the MessageManager, you have to do this while calling the function and parsing the returned list.
	 * 
	 * @param author An identity or own identity.
	 * @return All messages of the given identity except those of class OwnMessage.
	 */
	@SuppressWarnings("unchecked")
	public List<Message> getMessagesBy(FTIdentity author) {
		Query query = db.query();
		query.constrain(Message.class);
		query.constrain(OwnMessage.class).not();
		query.descend("mAuthor").constrain(author).identity();
		ObjectSet<Message> result = query.execute();
		return result;
	}
	
	/**
	 * Get a list of locally stored own messages of the given identity. 
	 * Locally stored means that only messages of class {@link OwnMessage} are returned.
	 * 
	 * This means that there is no guarantee that the returned messages have actually been inserted to Freenet.
	 * - The messages returned by this function can be considered as the outbox of the given identity.
	 * 
	 * If you want a list of messages which is actually downloadable from Freenet, see {@link getMessagesBy}.
	 * 
	 * @param author The author of the messages.
	 * @return All own messages of the given own identity.
	 */
	@SuppressWarnings("unchecked")
	public synchronized List<OwnMessage> getOwnMessagesBy(FTOwnIdentity author) {
		Query query = db.query();
		query.constrain(OwnMessage.class);
		query.descend("mAuthor").constrain(author).identity();
		return query.execute();
	}

	public IdentityManager getIdentityManager() {
		return mIdentityManager;
	}

}
