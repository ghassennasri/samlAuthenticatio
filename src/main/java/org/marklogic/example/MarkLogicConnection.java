package org.marklogic.example;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.DatabaseClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.marklogic.client.document.DocumentWriteSet;
import com.marklogic.client.document.XMLDocumentManager;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.InputStreamHandle;


public class MarkLogicConnection {
    private static final String USER_NAME="ghassen.nasri@marklogic.com";
    private static final String USER_PASSWORD="Omar2018";
    private static final String OKTA_APP_EMBED_LINK="https://dev-184498.okta.com/home/marklogicdev184498_marklogic_1/0oaz0ocqsSRk0Yo7n4x6/alnz0y4ncxgMhvxmR4x6";
    private static final String OKTA_DOMAIN="dev-184498.okta.com";
    private static final String ONELOGIN_DOMAIN="marklogic-support-dev";
    private static final String ONELOGIN_APPID="1264289";


    private static final Logger logger = LoggerFactory.getLogger(MarkLogicConnection.class);
    private static DatabaseClient client;

    public static void load(String path,String []args) {

        try {
            if(args!=null&&args.length>0&&"OKTA".equals(args[0]))
                client = DatabaseClientFactory.newClient("gnatestvm.eng.marklogic.com", 8030,
                    SAMLClientAuthorizer.makeOktaSAMLAuthContext(USER_NAME,USER_PASSWORD,OKTA_DOMAIN,OKTA_APP_EMBED_LINK)

            );
            else
                client = DatabaseClientFactory.newClient("gnatestvm.eng.marklogic.com", 8040,
                        SAMLClientAuthorizer.makeOneLoginAMLAuthContext(USER_NAME,USER_PASSWORD,ONELOGIN_DOMAIN,ONELOGIN_APPID)

                );
            // create an XMLDocumentManager
            XMLDocumentManager docMgr = client.newXMLDocumentManager();
            // get a stream of all xml files of the directory
            Stream<Path> fileStream = Files.list(Paths.get(path)).filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".xml"));
            // Create and build up the batch
            DocumentWriteSet batch = docMgr.newWriteSet();

            Consumer<Path> addToBatch = filePath -> {
                InputStream docStream = null;
                try {
                    docStream = new BufferedInputStream(new FileInputStream(filePath.toString()));
                } catch (FileNotFoundException e) {
                    logger.error("A problem occured with File " + filePath.getFileName(), e);
                }

                // create a handle on the content
                InputStreamHandle contentHandle = new InputStreamHandle(docStream);

                String uri = args[0]+"/songs/" + filePath.getFileName();
                // specific metadata handle will handle dataTime property fragment for each
                // document
                DocumentMetadataHandle docSpecificMetadata = new DocumentMetadataHandle()
                        .withProperty("dateTime", Calendar.getInstance()).withCollections(args[0]);
                batch.add(uri, docSpecificMetadata, contentHandle);
            };

            fileStream.forEach(addToBatch);

            docMgr.write(batch);

            logger.info("wrote " + Files.list(Paths.get(path)).filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".xml")).count() + " songs to the  DB");


        } catch (Exception e) {
            logger.error("Exception occured when writing documents", e);

        } finally {

            if (client != null)
                client.release();
        }

    }

    /**
     * @param args
     */
    public static void main(String[] args) {

        String pathToFile = "C:\\Marklogic prep\\Marklogic trainings\\java training\\d1java\\mls-developer-java\\Unit06\\top-songs-source\\songs";
        load(pathToFile,args);
    }

}