import groovy.xml.MarkupBuilder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import groovy.xml.MarkupBuilder
/**
Creates a Sub.Tasks base don the Image IDs field. 
 */
 




//Get issue Property
def issueKey = issue.key
def projectKey = 'FOA'
// Get custom fields
def customFields = get("/rest/api/2/field")
        .asObject(List)
        .body
        .findAll {
            (it as Map).custom
        } as List<Map>
def thisIssue = get("/rest/api/2/issue/${issueKey}").asObject(Map).body
def thisIssueFields = thisIssue.find { it.key == "fields" }?.value as Map
def thisIssueLinks = thisIssueFields.find { it.key == "issuelinks" }?.value as Map
/**
 * GET CUSTOM FIELDS
 */
//Image ID
def imageIDsFieldid = customFields.find { it.name == 'Image IDs' }?.id 
//Get field Values
def summaryFieldvalue = 'Metadata and processing'
def imageIDstFieldvalue = thisIssue.fields[imageIDsFieldid] as String
//trim values 
imageIDstFieldvalue = imageIDstFieldvalue.replaceAll("\\s",",")
try {
def ImageIDChange = changelog?.items.find {it['field'] == 'Image IDs'} as Map
if (ImageIDChange) {
    
    
    logger.info("------------------image IDs field changed")
    
    def fromString = ImageIDChange.fromString
        def fromStringFormatted = fromString.replaceAll("[\\n\\t ]", ",")
        logger.info("old formatted " + fromStringFormatted)
        
        
           def toString = ImageIDChange.toString
        def toStringFormatted = toString.replaceAll("[\\n\\t ]", ",")
        logger.info("new formatted " + toStringFormatted)
        
        
           //if additional Ids are Added 
        if (toStringFormatted.size() + 1 > fromStringFormatted.size() + 1) {
    
            logger.info("new formatted " + toStringFormatted)
            logger.info("new: " + (toStringFormatted.size() + 1) )
            logger.info("old: " + (fromStringFormatted.size() + 1) )
            def newIDsString = toStringFormatted.substring(fromStringFormatted.size())
            //Create RETURN tickets based on the new image IDs field
            if (newIDsString != null) {
                String[] listfNewIds = newIDsString.split(/\s+|,/)
             
             
                           createReturnTicket(listfNewIds, issueKey, projectKey, summaryFieldvalue)
                
            }
        }
        
    
    
    
    
}else { //if no new IDs are present
    
    logger.info("field ID IS NOT UPDATED - NOTHING TO DO")
      
    
}   
} catch (Exception e) {
    
 //Exception Error 
   logger.info("Ticket is being created - create a return for all IDs")
    //Create RETURN tickets based on the image IDs field
    if (imageIDstFieldvalue != null) {
        String[] imageIDs = imageIDstFieldvalue.split(/\s+|,/)
    
       createReturnTicket(imageIDs, issueKey, projectKey, summaryFieldvalue)
    }
   
   
    
}
/***************************************** HELPER METHODS **********************************
 */
 
//Create a new Customer Return ticket
String createReturnTicket(imagesStrings, issueKey, projectKey, summaryFieldvalue) {
    def IssueTypeID = getIssueTypeIdFromName('Image Activity')
    def imageID = getFieldIdFromName('Activity ID')
  
   
    //Loop Through all the IDs, and Create a new Return Ticket
    for (ids in imagesStrings) {
        def id = ids as String 
        if (id?.trim()){
 
        post("/rest/api/2/issue")
                .header("Content-Type", "application/json")
                .body(
                        [
                                fields: [
                                        summary              : "" + summaryFieldvalue + ": " + id,
                                        description          : "Image ID " + id,
                                        (imageID)            : "" + id,
                                        project              : [
                                                key: projectKey
                                        ],
                                        issuetype            : [
                                                id: IssueTypeID
                                                //issuetype: "Su"
                                        ],
                                        
                                          parent            : [
                                                key: issueKey
                                        ]
                                ]
                        ])
                .asString()
    } //null check 
                
    } //end of Loop
} //end of function
//end of function
//Get issue type ID, by Name
String getIssueTypeIdFromName(issueType) {
    def issueTypeObject = get('/rest/api/2/issuetype').asObject(List).body.find {
        (it as Map).name == issueType
    } as Map
    issueTypeObject.id
}
//Get Field ID, from Name
String getFieldIdFromName(fieldName) {
             System.out.println("get field id ----" + fieldName)
    def customFieldObject = get('/rest/api/2/field').asObject(List).body.find {
        (it as Map).name == fieldName
    } as Map
    customFieldObject.id
}