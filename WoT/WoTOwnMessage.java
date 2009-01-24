package plugins.Freetalk.WoT;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import plugins.Freetalk.Board;
import plugins.Freetalk.FTIdentity;
import plugins.Freetalk.FTOwnIdentity;
import plugins.Freetalk.Message;
import plugins.Freetalk.OwnMessage;
import plugins.Freetalk.exceptions.InvalidParameterException;
import freenet.keys.FreenetURI;
import freenet.support.HexUtil;

public class WoTOwnMessage extends OwnMessage {

	public static OwnMessage construct(Message newParentThread, Message newParentMessage, Set<Board> newBoards, Board newReplyToBoard, FTOwnIdentity newAuthor,
			String newTitle, Date newDate, String newText, List<Attachment> newAttachments) throws InvalidParameterException {
		return new WoTOwnMessage(newParentThread, newParentMessage, newBoards, newReplyToBoard, newAuthor, newTitle, newDate, newText, newAttachments);
	}

	protected WoTOwnMessage(Message newParentThread, Message newParentMessage, Set<Board> newBoards, Board newReplyToBoard, FTOwnIdentity newAuthor,
			String newTitle, Date newDate, String newText, List<Attachment> newAttachments) throws InvalidParameterException {
		super(null, null, generateRandomID(newAuthor), null, (newParentThread == null ? null : newParentThread.getURI()),
			  (newParentMessage == null ? null : newParentMessage.getURI()),
			  newBoards, newReplyToBoard, newAuthor, newTitle, newDate, newText, newAttachments);
	}

	protected static String generateRandomID(FTIdentity author) {
		return HexUtil.bytesToHex(author.getRequestURI().getRoutingKey()) + "@" + UUID.randomUUID();
	}

	/**
	 * Generate the insert URI for a message.
	 */
	public synchronized FreenetURI getInsertURI() {
		return FreenetURI.EMPTY_CHK_URI;
	}

}