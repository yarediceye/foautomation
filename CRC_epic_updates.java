import groovy.xml.MarkupBuilder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import groovy.xml.MarkupBuilder


/**
 This scripts, parses the Customer Return image IDs field  and does the following
 - Creates a new issueTypeof of RETURN for each Image ID Provides
 - Creates a new Sub-Task
 - When/if the Customer Image ID if  updated and a new ID is added, it will create new RETURNS for the new IDs
 - Updates the Primary Root Cause field when a new Link is added

 Deployment To-Do:
 Add Trigger: issue.issueType.name == "Epic"Make 
 Make sure projectKey  value is set to 'CRC'
 Make sure the folloiwng are added on the Script Listener,
        -Issue Created
        -Issue Updated
        -Option Issuelinks Changed

 */


//Get issue Property
def issueKey = issue.key
def projectKey = 'CRC'
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
def imageIDsFieldid = customFields.find { it.name == 'Image IDs included in return' }?.id //Technical Contact
def techContactFieldid = customFields.find { it.name == 'Tech contact' }?.id //CS Contact
def csContactFieldid = customFields.find { it.name == 'CS contact' }?.id //Sales Contact
def salesContactFieldid = customFields.find { it.name == 'Customer Main PoC' }?.id //Epic Link
def epicNameFieldid = customFields.find { it.name == 'Epic Name' }?.id //Epic Name
def returnOriginFieldid = customFields.find { it.name == 'Origin' }?.id //Return Origin
def filterLinkFieldid = customFields.find { it.name == 'Filter Link' }?.id //Filter Link
def DashboardLinkFieldid = customFields.find { it.name == 'Dashboard Link' }?.id //Filter Link


//Get field Values
def summaryFieldvalue = thisIssueFields.summary
def imageIDstFieldvalue = thisIssue.fields[imageIDsFieldid] as String
//trim values 
imageIDstFieldvalue = imageIDstFieldvalue.replaceAll("\\s",",")

def techContactFieldvalue = thisIssue.fields[techContactFieldid]
def csContactFieldvalue = thisIssue.fields[csContactFieldid]
def salesContactFieldvalue = thisIssue.fields[salesContactFieldid]
def epicNameFieldvalue = thisIssue.fields[epicNameFieldid] as String
def returnOriginFieldvalue = thisIssue.fields[returnOriginFieldid] as String
def newReturn=false //Flag to check if this is a new return or editing of an existing return 



/**
 // 1. Create RETURNS from the IDs provided in the Return Image IDs field
 */

//Get all Returns with the ID of the Current EPic
def epicSearch = "project%20%3D%20CRC%20AND%20%27Epic%20Link%27%20%3D%20" + issueKey
def epicReturns = get('/rest/api/2/search?jql=' + epicSearch).asObject(Map).body
def epicReturnsIssues = epicReturns.find {it.key == "issues"}?.value as Map

//get all RETURN IMAGE IDs
def returnissueImageIDField = getFieldIdFromName("Return Image ID")


//Check if this is an udpate and the "Image IDs included in return" as been updated
try {

    def ImageIDChange = changelog?.items.find {it['field'] == 'Image IDs included in return'} as Map



    //Check fi the "Iamge IDs inluced in return" field has changed
    if (ImageIDChange) {
        //logger.info("Image IDs field is UPDATED")

        def fromString = ImageIDChange.fromString
        def fromStringFormatted = fromString.replaceAll("[\\n\\t ]", ",")
        logger.info("old formatted" + fromStringFormatted)


        def toString = ImageIDChange.toString
        def toStringFormatted = toString.replaceAll("[\\n\\t ]", ",")
        logger.info("new formatted" + toStringFormatted)


        // logger.info("old"+fromStringFormatted)
        //logger.info("new"+toStringFormatted)
        //logger.info("dff"+newIDsString)

        //if additional Ids are Added 
        if (toStringFormatted.size() + 1 > fromStringFormatted.size() + 1) {
            def newIDsString = toStringFormatted.substring(fromStringFormatted.size())
            //Create RETURN tickets based on the new image IDs field
            if (newIDsString != null) {
                String[] listfNewIds = newIDsString.split(/\s+|,/)
                returnOriginFieldvalue = "Preemptive investigation"
                createReturnTicket(listfNewIds, issueKey, projectKey, techContactFieldid, techContactFieldvalue, csContactFieldid, csContactFieldvalue, salesContactFieldid, salesContactFieldvalue, summaryFieldvalue, returnOriginFieldid, returnOriginFieldvalue)
            }

        }


        //If the Image IDs not edited - do nothing    
    } else {
        //logger.info("field ID IS NOT UPDATED - NOTHING TO DO")

    }


    //This
} catch (Exception e) {


    logger.info("Ticket is being created - create a return for all IDs")
    newReturn=true
    //Create RETURN tickets based on the image IDs field
    if (imageIDstFieldvalue != null) {
        String[] imageIDs = imageIDstFieldvalue.split(/\s+|,/)
        returnOriginFieldvalue = "Actual return"
        createReturnTicket(imageIDs, issueKey, projectKey, techContactFieldid, techContactFieldvalue, csContactFieldid, csContactFieldvalue, salesContactFieldid, salesContactFieldvalue, summaryFieldvalue, returnOriginFieldid, returnOriginFieldvalue)


    }

}





/**
 * 2. Create a Filter for this Epic, this will be added to the Epic
*/


//only update the Dashboard and Filter if this is a new return 
if(newReturn == true) {
    

def jqlSearch = "project = 'CRC' AND issuetype = 'Customer Return' AND 'Epic Link' = '" + issueKey + "' ORDER BY created DESC"
def filterid = post("rest/api/3/filter")
        .header("Content-Type", "application/json")
        .body([
                "jql" : jqlSearch,
                "name": projectKey + ":" + summaryFieldvalue + " Filter",
        ])
        .asObject(Map).body.id


def fieldcolumns = "['issuetype','issuekey','summary']"
post("/rest/api/3/filter/${filterid}/permission")
        .header('Content-Type', 'application/json')
        .body([
                "type"     : "group",
                "groupname": "jira-servicedesk-users",
        ])
        .asObject(Map)


//Update Epic - Filter
updateEpic(issueKey, filterLinkFieldid, filterid, "Filter")



/**
 * 3. Create a DASHBOARD for this Epic, this will be added to the Epic.
 * Clone Dashboard that will be used as a template
*/

def dashboardID = post("rest/api/3/dashboard/10456/copy")
        .header("Content-Type", "application/json")
        .body([
                name            : "CRC: " + summaryFieldvalue,
                sharePermissions: [
                        [
                                type : "group",
                                group: [
                                        name: "jira-servicedesk-users"
                                ]
                        ]
                ]
        ]
        )
        .asObject(Map).body.id




System.out.println("new return" + newReturn)

//Update the Dashboard Link ID field on the Epic
updateEpic(issueKey, DashboardLinkFieldid, dashboardID, "Dashboard")




} //Close newReturn flag check 



/***************************************** HELPER METHODS **********************************
 */

 



//Update EPIC with newly Created Filter
def updateEpic(String epickey, filterLinkFieldid, id, fieldName) {
    def link_URL = ""
    if (fieldName == "Filter") {
        link_URL = "https://iceyedev.atlassian.net/issues/?filter=" + id
    } else {
        link_URL = "https://iceyedev.atlassian.net/secure/Dashboard.jspa?selectPageId=" + id
    }
    //Update the FOA ticket with the latest most updated FOA ticket
    def resp = put("/rest/api/2/issue/${epickey}")
            .header('Content-Type', 'application/json')
            .body([
                    fields: [
                            (filterLinkFieldid): link_URL
                    ]
            ])
            .asString()
    assert resp.status == 204
}


//Update the Primary Root Cause field
def updatePrimaryRootCause(String FOAKey, String primaryTicketKey, String rootCauseFieldid) {
    //Update the FOA ticket with the latest most updated FOA ticket
    // `entry` is a map entry
    def thisIssue = get("/rest/api/2/issue/${FOAKey}").asObject(Map).body
    def currentrootCauseFieldid = thisIssue.fields[rootCauseFieldid]
    if (currentrootCauseFieldid != primaryTicketKey) {
        def resp = put("/rest/api/2/issue/${FOAKey}")
                .header('Content-Type', 'application/json')
                .body([
                        fields: [
                                (rootCauseFieldid): primaryTicketKey
                        ]
                ])
                .asString()
        assert resp.status == 204
    }//end of if
}

//Create a new Customer Return ticket
String createReturnTicket(imagesStrings, issueKey, projectKey, techContactFieldid, techContactFieldvalue, csContactFieldid, csContactFieldvalue, salesContactFieldid, salesContactFieldvalue, summaryFieldvalue, returnOriginFieldid, returnOriginFieldvalue) {


    def IssueTypeID = getIssueTypeIdFromName('Customer Return')
    
    def epicID = getFieldIdFromName('Epic Link')
    def imageID = getFieldIdFromName('Return Image ID')

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
                                        description          : "Image id " + id,
                                        (epicID)             : issueKey,
                                        (imageID)            : "" + id,
                                        (techContactFieldid) : techContactFieldvalue,
                                        (csContactFieldid)   : csContactFieldvalue,
                                        (salesContactFieldid): salesContactFieldvalue,
                                        (returnOriginFieldid): [value: returnOriginFieldvalue] as Map,
                                        project              : [
                                                key: projectKey
                                        ],
                                        issuetype            : [
                                                id: IssueTypeID
                                        ]
                                ]
                        ])
                .asString()
    } //null check 
                
    } //end of Loop
} //end of function


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