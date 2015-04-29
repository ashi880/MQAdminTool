/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package MQApi.Utilities;

import MQApi.Connection.MQConnection;
import MQApi.Enums.LogType;
import MQApi.Enums.QueueType;
import MQApi.Logs.LogWriter;
import MQApi.Models.MQMessageIdModel;
import MQApi.Models.Query.ConnectionDetailModel;
import MQApi.PCF.MQPCF;
import MQApi.QueryModel.MQMessageListResult;
import MQApi.QueryModel.MQMessageListResult.MessageDetailModel;
import MQApi.QueryModel.MQQueuePropertyModel;
import UI.Helpers.DateTimeHelper;
import com.ibm.mq.MQC;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.constants.MQConstants;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.io.StreamCorruptedException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.logging.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.swing.JProgressBar;

/**
 *
 * @author jzhou
 */
public class MQUtility {
    
    private static int messageListDataLength = 50;

    public static boolean PutMessage(MQQueueManager queueManager, String queueName, MQMessage message){    
        MQQueue queue = null;
        try {
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_OUTPUT);
            queue.put(message);
            closeQueue(queue);
        } catch (Exception ex) {
            LogWriter.WriteToLog("MQUtility", "PutMessage", ex);
            closeQueue(queue);
            return false;
        }
        return true;
    }  
    
    public static int GetQueueDepth(MQQueueManager queueManager, String queueName){
        MQQueue queue = null;
        int result = 0;
        try {
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
            result = queue.getCurrentDepth();
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "GetQueueDepth", ex);
        }
        closeQueue(queue);
        return result;
    }
    
    public static MQMessage GetMessage(MQQueueManager queueManager, String queueName, MQMessageIdModel messageIdModel, boolean matchPosition) throws MQException{
        MQQueue queue = null;
        MQMessage message = new MQMessage();
        message.messageId = messageIdModel.MessageId;
        message.correlationId = messageIdModel.CorrelationdId;
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = CMQC.MQGMO_BROWSE_NEXT;
        options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
        try {                 
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_OUTPUT);
            if(matchPosition){
                setQueueCursor(queue, messageIdModel.position);
            }
            queue.get(message, options);

        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "GetMessage", ex);
            closeQueue(queue);
            throw ex;           
        }
        closeQueue(queue);
        return message;                
    }
    
    public static void UpdateMessage(MQQueueManager queueManager, String queueName, MQMessage message) throws MQException{
        MQQueue queue = null;
        MQPutMessageOptions options = new MQPutMessageOptions();
        options.options = CMQC.MQPMO_SET_ALL_CONTEXT;
        MQMessageIdModel oldId = new MQMessageIdModel();
        oldId.CorrelationdId = message.correlationId;
        oldId.MessageId = message.messageId;
        try{
            ComsumeSelectedMessages(queueManager, queueName, oldId);
        }
        catch(MQException ex){
            LogWriter.WriteToLog("MQUtility", "UpdateMessage", ex);
            closeQueue(queue);
            throw ex;
        }
        try {            
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_OUTPUT | CMQC.MQOO_SET_ALL_CONTEXT);
            queue.put(message, options);
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "UpdateMessage", ex);
            closeQueue(queue);
            throw ex;           
        }
        closeQueue(queue);              
    }

    public static void UpdateMessageToSamePosition(MQQueueManager queueManager, String queueName, MQMessage message) throws MQException, Exception{
        MQMessageIdModel id = new MQMessageIdModel();
        id.CorrelationdId = message.correlationId;
        id.MessageId = message.messageId;
        String fileName = "UpdateTemp.msg";
        BackupAndRemoveMessageFromSelectedPosition(queueManager, queueName, fileName, id);
        try{
            UpdateMessage(queueManager, queueName, message);
        }
        catch(MQException ex){
            LogWriter.WriteToLog("MQUtility", "UpdateMessageToSamePosition", ex);
            RestoreMessageFromFile(queueManager, queueName, fileName, null, false, false);
            removeFile(fileName);  
            throw ex;
        }
        RestoreMessageFromFile(queueManager, queueName, fileName, null, false, false);
        removeFile(fileName);            
    }
    
    public static MQMessageListResult GetMessageList(MQQueueManager queueManager, String queueName, int numOfMessageRequired,boolean isAlias){
        return GetMessageList(queueManager, queueName, numOfMessageRequired, isAlias, null, 0);
    }
          
    public static MQMessageListResult GetMessageList(MQQueueManager queueManager, String queueName, int numOfMessageRequired,boolean isAlias, MQMessageIdModel fromId, int fromPosition){
        MQMessageListResult result = new MQMessageListResult();
        MQQueue queue = null;
        try {
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
            int queueDepth = queue.getCurrentDepth();
            int index = numOfMessageRequired > 0 &&  numOfMessageRequired < (queueDepth - fromPosition + 1) ? numOfMessageRequired : (queueDepth - fromPosition + 1);
            int position = fromPosition;
            MQMessage message = new MQMessage();
            MQGetMessageOptions options = new MQGetMessageOptions();
            options.options = CMQC.MQGMO_BROWSE_NEXT;
            options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
            if(fromId != null){
                message.messageId = fromId.MessageId;
                message.correlationId = fromId.CorrelationdId;
                try{
                    queue.get(message, options);
                }
                catch(MQException ex){
                    if(!setQueueCursor(queue, fromPosition)){
                        closeQueue(queue);
                        return result;
                    }                                        
                }
            }
            while(index > 0){ 
                resetMessage(message);
                try{
                    queue.get(message, options);
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                        break;
                    }
                    else{
                        LogWriter.WriteToLog("MQUtility", "GetMessageList", ex);
                        throw ex;
                    }
                }
                index--;
                MessageDetailModel model = turnToMessageModel(result, message, position);
                result.Messages.add(model);
                position ++;
            }            
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "GetMessageList", ex);
            result.QuerySuccess = false;
            result.ErrorMessage = getMQReturnMessage(ex.getCompCode(), ex.getReason());
            closeQueue(queue);
            return result;
        }
        closeQueue(queue);
        result.QuerySuccess = true;
        return result;
    }

    public static void ComsumeAllMessages(MQQueueManager queueManager, String queueName, JProgressBar progressBar, boolean forceOpenGet, boolean isAlias) throws MQException{
        MQQueue queue = null;
        try {
            int openQueueOptions;
            
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            openQueueOptions = forceOpenGet == true? CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_SET : CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED;
            queue = queueManager.accessQueue(queueName, openQueueOptions);
            boolean isGetOpen = queue.getInhibitGet() == MQConstants.MQQA_GET_ALLOWED;
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_ALLOWED);
            }
            int queueDepth = queue.getCurrentDepth();
            int index = queueDepth;
            MQGetMessageOptions options = new MQGetMessageOptions();
            MQMessage message = new MQMessage();
            options.matchOptions = MQConstants.MQMO_NONE;
            options.options = CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
            while(index > 0){               
                try{
                    index--;
                    if(progressBar != null){
                        int value = ((queueDepth - index)*100)/queueDepth;
                        progressBar.setValue(value);
                    }
                    queue.get(message, options, 1);
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){                        
                        continue;
                    }  
                    else if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                        break;
                    }
                    else{
                        LogWriter.WriteToLog("MQUtility", "ComsumeAllMessages",ex);
                        throw ex;
                    }
                }               
            }
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_INHIBITED);
            }
            closeQueue(queue);
            if(progressBar != null){
                progressBar.setValue(100);
            }
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "ComsumeAllMessages",ex);
            closeQueue(queue);
            throw ex;
        }

    }
    
    public static void ComsumeSelectedMessages(MQQueueManager queueManager, String queueName, MQMessageIdModel id) throws MQException{
        ArrayList<MQMessageIdModel> ids = new ArrayList<MQMessageIdModel>();
        ids.add(id);
        queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
        ComsumeSelectedMessages(queueManager, queueName, ids, null, false, false);
    }
    
    public static void ComsumeSelectedMessages(MQQueueManager queueManager, String queueName, ArrayList<MQMessageIdModel>ids, JProgressBar progressBar, boolean forceOpenGet, boolean isAlias) throws MQException {
        MQQueue queue = null;
        try {
            int openQueueOptions;
            if(isAlias){
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            }
            openQueueOptions = forceOpenGet == true? CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_SET : CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED;
            queue = queueManager.accessQueue(queueName, openQueueOptions);
            boolean isGetOpen = queue.getInhibitGet() == MQConstants.MQQA_GET_ALLOWED;
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_ALLOWED);
            }
            int i = 1;
            MQMessage message = new MQMessage();
            MQGetMessageOptions options = new MQGetMessageOptions();
            for(MQMessageIdModel id : ids){
                options.options = CMQC.MQGMO_ACCEPT_TRUNCATED_MSG;
                options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;                
                message.messageId = id.MessageId;
                message.correlationId = id.CorrelationdId;
                
                if(progressBar != null){
                    int value = i*100/ids.size();
                    progressBar.setValue(value);
                }
                i++;
                try{
                    queue.get(message, options, 1);
                }
                catch(MQException ex){
                    if(ex.getReason() == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
                        continue;
                    }   
                    else{
                        throw ex;
                    }
                }
            }
            if(forceOpenGet == true && isGetOpen == false){
                queue.setInhibitGet(MQConstants.MQQA_GET_INHIBITED);
            }
            closeQueue(queue);
            if(progressBar != null){
                progressBar.setValue(100);
            }
        } catch (MQException ex) {
            LogWriter.WriteToLog("MQUtility", "ComsumeSelectedMessages",ex);
            closeQueue(queue);
            throw ex;
        }

    }
    
    public static String getMQReturnMessage(int compCode, int reason){
            String msg = MQConstants.lookupCompCode(compCode) + " : (" + MQConstants.lookupReasonCode(reason) +" )";
            msg = msg.replace("_", " ");
            msg =  msg.toLowerCase();
            msg = msg.replace("mqcc", "Command");
            msg = msg.replace("mqrccf", "");
            msg = msg.replace("mqrc", "");
            return msg;
    }

    public static void BackupMessageToFile(MQQueueManager queueManager, String queueName, String filePath, JProgressBar progressBar, ArrayList<MQMessageIdModel>ids, boolean isCompress, boolean isAlias) throws Exception{
            FileOutputStream fileOutPutStream = null;
            BufferedOutputStream bufferedOutputStream = null;
            ObjectOutputStream  objectOutputStream = null;
            GZIPOutputStream gzipOutStream = null;            
            MQQueue queue = null;
            if(isAlias)
                queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);
            try{
                queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE);
                int queueDepth = queue.getCurrentDepth();            
                if(queueDepth > 0){
                    fileOutPutStream = new FileOutputStream(filePath);
                    bufferedOutputStream = new BufferedOutputStream(fileOutPutStream);
                    if(isCompress == true){
                        gzipOutStream = new GZIPOutputStream(bufferedOutputStream);
                        objectOutputStream = new ObjectOutputStream(gzipOutStream);
                    }
                    else{
                        objectOutputStream = new ObjectOutputStream(bufferedOutputStream);
                    }
                    objectOutputStream.writeObject("MQAdminToolQueueDataFile");
                    objectOutputStream.writeInt(0x10001);
                    objectOutputStream.writeObject(queueName);
                    objectOutputStream.writeInt(ids == null? queueDepth : ids.size());
                    int index = queueDepth;
                    if(ids == null){
                        while(index > 0){
                            MQMessage message = new MQMessage();
                            MQGetMessageOptions options = new MQGetMessageOptions();
                            options.options = CMQC.MQGMO_BROWSE_NEXT;
                            queue.get(message, options);
                            writeMessageToStream(message, objectOutputStream);
                            index--;
                            if(progressBar != null){
                                int value = ((queueDepth - index)*100)/queueDepth;
                                progressBar.setValue(value);
                            }
                        }
                    }
                    else{
                        int i = 1;
                        for(MQMessageIdModel id : ids){
                            MQMessage message = new MQMessage();
                            message.messageId = id.MessageId;
                            message.correlationId = id.CorrelationdId;
                            MQGetMessageOptions options = new MQGetMessageOptions();
                            options.options = CMQC.MQGMO_BROWSE_NEXT;
                            options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;
                            queue.get(message, options);   
                            writeMessageToStream(message, objectOutputStream);
                            if(progressBar != null){
                                int value = (i*100)/ids.size();
                                progressBar.setValue(value);
                            }  
                            i++;
                        }
                    }
                    closeQueue(queue);
                    objectOutputStream.flush();
                    if(gzipOutStream != null){
                        gzipOutStream.flush();
                    }
                    bufferedOutputStream.flush();
                    disposeOutputStreamObject(fileOutPutStream, bufferedOutputStream,gzipOutStream, objectOutputStream);
                }
                else {
                    closeQueue(queue);
                    throw new Exception("Queue depth is 0");
                }
            }catch(IOException ex){
                LogWriter.WriteToLog("MQUtility", "BackupMessageToFile",ex);
                disposeOutputStreamObject(fileOutPutStream, bufferedOutputStream, gzipOutStream, objectOutputStream);
                removeFile(filePath);
                closeQueue(queue);
                throw new Exception("File access error");
            }
            catch(MQException ex){
                LogWriter.WriteToLog("MQUtility", "BackupMessageToFile",ex);
                disposeOutputStreamObject(fileOutPutStream, bufferedOutputStream, gzipOutStream, objectOutputStream);
                removeFile(filePath);
                closeQueue(queue);
                throw new Exception(getMQReturnMessage(ex.getCompCode(), ex.getReason()));                
            }
    
        
    }
 
    public static void RestoreMessageFromFile(MQQueueManager queueManager, String queueName, String filePath, JProgressBar progressBar, boolean isAlias, boolean checkQueueDepth) throws Exception{
        FileInputStream fileInputStream = null;
        ObjectInputStream objectInputStream = null;  
        GZIPInputStream gzipInStream = null;
        BufferedInputStream bufferedInputStream = null;
        MQQueue queue = null;
        if(isAlias)
            queueName = MQPCF.ResolveAliasBaseQueueName(queueManager, queueName);        
        try {
            try{
                fileInputStream = new FileInputStream(filePath);
                bufferedInputStream = new BufferedInputStream(fileInputStream);
                gzipInStream = new GZIPInputStream(bufferedInputStream);
                objectInputStream = new ObjectInputStream(gzipInStream);
            }catch(Exception ex){
                disposeInputStreamObject(fileInputStream,bufferedInputStream,gzipInStream, objectInputStream);
                fileInputStream = new FileInputStream(filePath);
                bufferedInputStream = new BufferedInputStream(fileInputStream);
                objectInputStream = new ObjectInputStream(bufferedInputStream);
            }
            
            try {
                String fileIdentifier = (String)objectInputStream.readObject();
                int intIdentifier = objectInputStream.readInt();
                if(fileIdentifier == null || !fileIdentifier.equals("MQAdminToolQueueDataFile") || intIdentifier != 0x10001){
                    throw new Exception("Not a valid messagae data file");
                }
            } catch (Exception ex) {
                throw new Exception("Not a valid messagae data file");
            }
            String originalQueueName = (String)objectInputStream.readObject();
            int totalNumOfMessages = objectInputStream.readInt();                  
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_OUTPUT | CMQC.MQOO_SET_ALL_CONTEXT | CMQC.MQOO_INPUT_EXCLUSIVE);
            if(checkQueueDepth){
                try{
                    int curDepth = queue.getCurrentDepth();
                    int maxDepth = queue.getMaximumDepth();                
                    int availableMsgNum = maxDepth - curDepth;
                    if(totalNumOfMessages > availableMsgNum ){
                        closeQueue(queue);
                        throw new Exception("Not enough avaliable queue depth");
                    }
                }
                catch(MQException ex){

                }
            }
            MQPutMessageOptions option = new MQPutMessageOptions();
            option.options = CMQC.MQPMO_SET_ALL_CONTEXT;
            for(int i = 0; i < totalNumOfMessages; i++){
                try{
                    MQMessage message = readMessageFromStream(objectInputStream);
                    queue.put(message, option);
                    if(progressBar != null){
                        int value = ((i + 1) * 100)/totalNumOfMessages;
                        progressBar.setValue(value);
                    }
                }
                catch(Exception ex){    
                    if(progressBar != null){
                        progressBar.setValue(100);
                    }
                    break;
                }

            }
            closeQueue(queue);
            disposeInputStreamObject(fileInputStream,bufferedInputStream,gzipInStream, objectInputStream);

        }
        catch (IOException ex){
            LogWriter.WriteToLog("MQUtility", "RestoreMessageFromFile",ex);
            disposeInputStreamObject(fileInputStream,bufferedInputStream,gzipInStream, objectInputStream);
            closeQueue(queue);
            throw new Exception("File access error");
        }
        catch(MQException ex){
            LogWriter.WriteToLog("MQUtility", "RestoreMessageFromFile",ex);
            disposeInputStreamObject(fileInputStream,bufferedInputStream,gzipInStream, objectInputStream);
            closeQueue(queue);
            throw new Exception(getMQReturnMessage(ex.getCompCode(), ex.getReason())); 
        } 
    }
  
    public static String BytesToHex(byte[] bytes) {
        char[] hexArray = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
    
//private
    private static MessageDetailModel turnToMessageModel(MQMessageListResult result, MQMessage message, int position){
        MessageDetailModel model = result.new MessageDetailModel();
        model.MessageId = message.messageId;
        model.CorrelationId = message.correlationId;
        model.ApplicationIdentifyData = message.applicationIdData.trim();
        model.ApplicationOriginalData = message.applicationOriginData.trim();
        try {
            model.DataLength = Integer.toString(message.getDataLength());
        } catch (IOException ex) {
            model.DataLength = "0";
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
        model.Encoding = Integer.toString(message.encoding).trim();
        model.Expiry = message.expiry == MQConstants.MQEI_UNLIMITED? "Unlimited" : DateTimeHelper.GetCurrentAddSecondTimeStamp(message.expiry / 10);
        model.Format = message.format.trim();
        model.MessageType = getMQMEessageType(message.messageType);
        model.Persistence = message.persistence == 0 ? "Not persistence" : "Persistence";
        model.Priority = Integer.toString(message.priority).trim();
        model.PutApplicationName = message.putApplicationName.trim();
        model.PutDateTime =  message.putDateTime != null ? DateTimeHelper.GetCustomTimeStamp(message.putDateTime.getTime()) : null;
        model.Position = Integer.toString(position).trim();
        model.ReplyToQueue = message.replyToQueueName.trim();
        model.ReplyToQueueManager = message.replyToQueueManagerName.trim();
        model.TotalLength = Integer.toString(message.getTotalMessageLength()).trim();
        model.UserIdentifier = message.userId.trim();
        model.SequenceNumber = message.messageSequenceNumber;
        model.AccountToken = BytesToHex(message.accountingToken);
        model.MessageIdString = BytesToHex(message.messageId);
        model.Offset = message.offset;
        try {
            model.MessageData = message.readStringOfByteLength(message.getDataLength() <= messageListDataLength ? message.getDataLength() : messageListDataLength ).trim();
        } catch (IOException ex) {
            model.MessageData = "";
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
        return model;
    }
    
    private static String getMQMEessageType(int type){
        switch(type){
            case CMQC.MQMT_DATAGRAM :
                return "Datagram";
            case CMQC.MQMT_REQUEST:
                return "Request";
            case CMQC.MQMT_REPLY:
                return "Reply";
            case CMQC.MQMT_REPORT:
                return "Report";
            default:
                return Integer.toString(type);
        }
    }
   
    private static MQMessage readMessageFromStream(ObjectInputStream stream) throws IOException, ClassNotFoundException{
        MQMessage message = new MQMessage();
        message.report = stream.readInt();
        message.messageType = stream.readInt();
        message.expiry = stream.readInt();
        message.feedback = stream.readInt();
        message.encoding = stream.readInt();
        message.characterSet = stream.readInt();
        message.feedback = stream.readInt();
        message.format = (String)stream.readObject();
        message.priority = stream.readInt();
        message.persistence = stream.readInt();
        stream.readFully(message.messageId);
        stream.readFully(message.correlationId);
        message.backoutCount = stream.readInt();
        message.replyToQueueName = (String)stream.readObject();
        message.replyToQueueManagerName = (String)stream.readObject();
        message.userId = (String)stream.readObject();
        stream.readFully(message.accountingToken);
        message.applicationIdData = (String)stream.readObject();
        message.putApplicationType = stream.readInt();
        message.putApplicationName = (String)stream.readObject();
        message.putDateTime = (GregorianCalendar)stream.readObject();
        message.applicationOriginData = (String)stream.readObject();
        stream.readFully(message.groupId);
        message.messageSequenceNumber = stream.readInt();
        message.offset = stream.readInt();
        message.messageFlags = stream.readInt();
        message.originalLength = stream.readInt();
        int i = stream.readInt();
        byte buff[] = new byte[i];
        stream.readFully(buff);
        message.write(buff);
        return message;
    }
    
    private static void writeMessageToStream(MQMessage message, ObjectOutputStream stream){
        try
        {
            stream.writeInt(message.report);
            stream.writeInt(message.messageType);
            stream.writeInt(message.expiry);
            stream.writeInt(message.feedback);
            stream.writeInt(message.encoding);
            stream.writeInt(message.characterSet);
            stream.writeInt(message.feedback);
            stream.writeObject(message.format);
            stream.writeInt(message.priority);
            stream.writeInt(message.persistence);
            stream.write(message.messageId);
            stream.write(message.correlationId);
            stream.writeInt(message.backoutCount);
            stream.writeObject(message.replyToQueueName);
            stream.writeObject(message.replyToQueueManagerName);
            stream.writeObject(message.userId);
            stream.write(message.accountingToken);
            stream.writeObject(message.applicationIdData);
            stream.writeInt(message.putApplicationType);
            stream.writeObject(message.putApplicationName);
            stream.writeObject(message.putDateTime);
            stream.writeObject(message.applicationOriginData);
            stream.write(message.groupId);
            stream.writeInt(message.messageSequenceNumber);
            stream.writeInt(message.offset);
            stream.writeInt(message.messageFlags);
            stream.writeInt(message.originalLength);
            message.seek(0);
            stream.writeInt(message.getMessageLength());
            byte buff[] = new byte[message.getMessageLength()];
            message.readFully(buff);
            stream.write(buff);
        }
        catch(Exception ex)
        {
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
   
    private static void disposeOutputStreamObject(FileOutputStream fileOutPutStream,BufferedOutputStream bufferedOutputStream, GZIPOutputStream gzipOutStream , ObjectOutputStream  objectOutputStream ){
        if(objectOutputStream != null){
            try {
                objectOutputStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if(gzipOutStream != null){
            try {
                gzipOutStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if(bufferedOutputStream != null){
            try {
                bufferedOutputStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        if(fileOutPutStream != null){
            try {
                fileOutPutStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
    }

    private static void disposeInputStreamObject(FileInputStream fileInPutStream, BufferedInputStream bufferedInputStream, GZIPInputStream gzipInStream, ObjectInputStream  objectInputStream ){
        if(objectInputStream != null){
            try {
                objectInputStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if(gzipInStream != null){
            try {
                gzipInStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if(bufferedInputStream != null){
            try {
                bufferedInputStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }        
        if(fileInPutStream != null){
            try {
                fileInPutStream.close();
            } catch (IOException ex) {
                Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
    }

    private static void BackupAndRemoveMessageFromSelectedPosition(MQQueueManager queueManager, String queueName, String filePath,MQMessageIdModel id) throws Exception{
        FileOutputStream fileOutPutStream = null;
        BufferedOutputStream bufferedOutputStream = null;
        ObjectOutputStream  objectOutputStream = null;
        GZIPOutputStream gzipOutStream = null;            
        MQQueue queue = null;
        try{
            queue = queueManager.accessQueue(queueName, CMQC.MQOO_INQUIRE | CMQC.MQOO_BROWSE | CMQC.MQOO_INPUT_SHARED | CMQC.MQOO_OUTPUT);
            int queueDepth = queue.getCurrentDepth();            
            if(queueDepth > 0){
                fileOutPutStream = new FileOutputStream(filePath);
                bufferedOutputStream = new BufferedOutputStream(fileOutPutStream);
                gzipOutStream = new GZIPOutputStream(bufferedOutputStream);
                objectOutputStream = new ObjectOutputStream(gzipOutStream);

                objectOutputStream.writeObject("MQAdminToolQueueDataFile");
                objectOutputStream.writeInt(0x10001);
                objectOutputStream.writeObject(queueName);
                objectOutputStream.writeInt(queueDepth);
                MQMessage message = new MQMessage();
                message.messageId = id.MessageId;
                message.correlationId = id.CorrelationdId;
                MQGetMessageOptions options = new MQGetMessageOptions();
                options.options = CMQC.MQGMO_BROWSE_NEXT;
                options.matchOptions = MQConstants.MQMO_MATCH_MSG_ID  | MQConstants.MQMO_MATCH_CORREL_ID;                
                queue.get(message, options);
                ArrayList<MQMessageIdModel> idsToRemove = new ArrayList<MQMessageIdModel>();
                while(true){
                    try{
                        resetMessage(message);
                        options.options = CMQC.MQGMO_BROWSE_NEXT ;
                        options.matchOptions = CMQC.MQMO_NONE;
                        queue.get(message, options);
                        writeMessageToStream(message, objectOutputStream);
                        MQMessageIdModel deleteMsgId = new MQMessageIdModel();
                        deleteMsgId.MessageId = message.messageId;
                        deleteMsgId.CorrelationdId = message.correlationId;
                        idsToRemove.add(deleteMsgId);
                    }
                    catch(MQException ex){
                        if(ex.getReason() == MQConstants.MQRC_NO_MSG_AVAILABLE){
                            break;
                        }
                        else{
                            throw ex;
                        }
                    }
                }
                
                closeQueue(queue);
                objectOutputStream.flush();
                if(gzipOutStream != null){
                    gzipOutStream.flush();
                }
                bufferedOutputStream.flush();
                disposeOutputStreamObject(fileOutPutStream, bufferedOutputStream,gzipOutStream, objectOutputStream);
                ComsumeSelectedMessages(queueManager, queueName, idsToRemove, null, false, false);
            }
            else {
                closeQueue(queue);
                throw new Exception("Queue depth is 0");
            }
        }catch(IOException ex){
            LogWriter.WriteToLog("MQUtility", "BackupAndRemoveMessageFromSelectedPosition",ex);
            disposeOutputStreamObject(fileOutPutStream, bufferedOutputStream, gzipOutStream, objectOutputStream);
            removeFile(filePath);
            closeQueue(queue);
            throw new Exception("File access error");
        }
        catch(MQException ex){
            LogWriter.WriteToLog("MQUtility", "BackupAndRemoveMessageFromSelectedPosition",ex);
            disposeOutputStreamObject(fileOutPutStream, bufferedOutputStream, gzipOutStream, objectOutputStream);
            removeFile(filePath);
            closeQueue(queue);
            throw new Exception(getMQReturnMessage(ex.getCompCode(), ex.getReason()));                
        }
    
        
    }
    
    private static void removeFile(String filePath){
        File file = new File(filePath);
        if(file.exists()){
            file.delete();
        }
    }
    
    private static void closeQueue(MQQueue queue){
        if(queue != null){
            try {
                queue.close();
            } catch (MQException ex) {
                LogWriter.WriteToLog("MQUtility", "removeFile",ex);
            }
        }
    }
       
    private static boolean setQueueCursor(MQQueue queue, int position){
        MQMessage message = new MQMessage();
        MQGetMessageOptions options = new MQGetMessageOptions();
        options.options = CMQC.MQGMO_BROWSE_NEXT;
        options.matchOptions = CMQC.MQMO_NONE;
        try {
            int queueDepth = queue.getCurrentDepth();
            position = position > queueDepth ? queueDepth : position;
            for(int i = 0; i < position - 1; i++){
                queue.get(message, options);  
                resetMessage(message);
            }
        }
        catch (MQException ex) {
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        } 
        return true;
    }
    
    private static void resetMessage(MQMessage message){
        try {
            message.clearMessage();
            message.messageId = MQConstants.MQMI_NONE;
            message.correlationId = MQConstants.MQMI_NONE;
        } catch (IOException ex) {
            Logger.getLogger(MQUtility.class.getName()).log(Level.SEVERE, null, ex);
            message = new MQMessage();
        }

    }
    
}