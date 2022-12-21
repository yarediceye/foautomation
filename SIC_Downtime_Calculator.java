import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import static java.util.Calendar.*

/**
 NAME: SIC_Downtime_updates.groovy

 This script is triggered by the following
 - Issue Created
 - Issueink Deleted
 - Issue Created
 - Issue Updated
 - Option Issuelinks Changed

 Scope:
 - Update Total Business and Operational Time for SIC Ticket
 - Update Last Occurrence Field for SIC ticket
 - Update Total Customer Returns
 **/


final chronoUnit = ChronoUnit.MINUTES
final formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
def theDatetime = new Date()
def currentDateTime = theDatetime.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ") as String
def utc = TimeZone.getTimeZone('UTC')


//get Issue Key
def issueKey = ""
if (binding.hasVariable('issue')) {
    issueKey = issue.key as String
} else {
    def sourceIssueResponse = get("/rest/api/2/issue/${issueLink.sourceIssueId}").asObject(Map)
    assert sourceIssueResponse.status == 200
    def sourceIssueKey = sourceIssueResponse.body.key
    issueKey = sourceIssueKey
}


// GET values
def SICIssue = get("/rest/api/2/issue/${issueKey}").asObject(Map).body
def SICIssueFields = SICIssue.find { it.key == "fields" }?.value as Map
def SICIssueLinks = SICIssueFields.find { it.key == "issuelinks" }?.value as Map

/// Get custom fields
def customFields = get("/rest/api/2/field")
        .asObject(List)
        .body
        .findAll {
            (it as Map).custom
        } as List<Map>


/************************************************ FIELD IDS */
//FOA
def downtimeImpactFieldid = customFields.find { it.name == 'Downtime Impact' }?.id
def timeOfOccurrenceFieldid = customFields.find { it.name == 'Time of occurrence' }?.id
def timeReturnToOpsFieldid = customFields.find { it.name == 'Time of Return to Nominal Operations' }?.id
def opsDowntimeFieldid = customFields.find { it.name == 'Operational Downtime' }?.id
def bizDowntimeFieldid = customFields.find { it.name == 'Business Downtime' }?.id
def primaryRootCauseFieldid = customFields.find { it.name == 'Primary Root Cause' }?.id
def sicGroupFieldid = customFields.find { it.name == 'SIC Group' }?.id


//SIC
def opsTotalDowntimeFieldid = customFields.find { it.name == 'Total Operational Downtime Impact [h]' }?.id
def bizTotalDowntimeFieldid = customFields.find { it.name == 'Total Business Downtime Impact [h]' }?.id
def totalIncidentCountFieldid = customFields.find { it.name == 'Total Incident Count' }?.id
def lastOccurrenceFieldid = customFields.find { it.name == 'Latest Occurrence' }?.id
def totalCustomerReturnsFieldid = customFields.find { it.name == 'Total Number of Customer Returns' }?.id
def totalCustomerReturns = 0 as Float //Count of all CRC tickets
def currentLatestOccurrence = SICIssue.fields[lastOccurrenceFieldid] //get latest Occurrence field value


//TOTAL
def SICtotalOpsDowntime = 0 as float
def SICtotalBizDowntime = 0 as float
def SICtotalIncidentCount = 0 as float


//Latest
def latestFOAOccurance = []


/**
 * Iterate through all linked  FOA tickets, linked reason "Causes"
 * Project that will link to SIC tickets
 1. FOA - Factory Operation
 2.BR - Tempo Customer returns
 3. CRC - Customer Returns and complains
 4. OM- Operation Maintenance
 */
for (int i = 0; i < SICIssueLinks.size(); i++) {

    //get FOA ticket
    def LinkedIssue = SICIssueLinks.get(i) as Map

    //if cause is "Causes"
    if (LinkedIssue.type.outward == "causes") {
        def LinkedIssueKey = LinkedIssue.outwardIssue.key as String // get FOA ticket key

        if ((LinkedIssueKey.contains("FOA-")) || (LinkedIssueKey.contains("BR-")) || (LinkedIssueKey.contains("CRC-")) || (LinkedIssueKey.contains("OM-"))) {

            def getLinkedticketInfo = get("/rest/api/2/issue/${LinkedIssueKey}").asObject(Map).body
            def linkedIssueFields = getLinkedticketInfo.find { it.key == "fields" }?.value as Map

            //get field values
            String downtimeImpactFieldvalue = getLinkedticketInfo.fields[downtimeImpactFieldid]
            String timeOfOccurrenceValue = getLinkedticketInfo.fields[timeOfOccurrenceFieldid]
            String timeofReturnToOpsValue = getLinkedticketInfo.fields[timeReturnToOpsFieldid]
            String primaryRootCauseValue = getLinkedticketInfo.fields[primaryRootCauseFieldid]


            String createdDateValue = linkedIssueFields.created


            //Count CRC Specific Tickets, for CRC PROJECTS
            if (LinkedIssueKey.contains("CRC-")) {

                def crcIssueType = linkedIssueFields.issuetype.name

                //Only Count if it is a Customer Return ticket
                if (crcIssueType == "Customer Return") {
                    totalCustomerReturns = totalCustomerReturns + 1
                    logger.info("Count this return, issueType=" + crcIssueType)
                }


            }


            //For FOA Tickets
            if (LinkedIssueKey.contains("FOA-")) {


                /****************************** UPDATE THE SIC GROUP FIELD *********************************************/
                //Go to the FOA ticket
                //Check if the Primary Root cause ticket is equals to this SIC ticket
                //If so, change the FOA SIC Group == SIC Group value here

                if (issueKey == primaryRootCauseValue) {
                    //Compary the current value and assign it a new one if it NEW
                    def thisIssue = get("/rest/api/2/issue/${issueKey}").asObject(Map).body
                    def SIC_GroupFieldid = thisIssue.fields[sicGroupFieldid]


                    logger.info("Root cause Found " + SIC_GroupFieldid)

                    def resp = put("/rest/api/2/issue/${LinkedIssueKey}")
                            .header('Content-Type', 'application/json')
                            .body([
                                    fields: [
                                            (sicGroupFieldid): SIC_GroupFieldid
                                    ]

                            ])
                            .asString()
                    assert resp.status == 204

                }
            } //end of FOA If


            //***Update Time of Occurrence Field
            //If Time of Occurrence is NULL, then Created Date as time of Occurrence
            if (timeOfOccurrenceValue == null) {
                timeOfOccurrenceValue = createdDateValue
            }

            //2. If time of Returnto Nominal Operations is NULL, SET it to the current Timestap
            if (timeofReturnToOpsValue == null) {
                timeofReturnToOpsValue = currentDateTime
            }
            latestFOAOccurance.add(timeOfOccurrenceValue)
            logger.info("Added time " + timeOfOccurrenceValue)


            //If Downtime Impact if empty, and time of return is greater than or equal to time of occurance
            if (downtimeImpactFieldvalue != null && (timeofReturnToOpsValue >= timeOfOccurrenceValue)) {
                // def theDate = new Date()
                Date todayDate = Date.parse("yyyy-MM-dd", theDatetime.format("yyyy-MM-dd"), utc)
                Date OccurrenceDate = Date.parse("yyyy-MM-dd", timeOfOccurrenceValue, utc)


                //Calculate Total Downtime Values
                def FOAopsDowntime = 0
                def FOAbizDowntime = 0


                //OPERATIONAL
                if (opsDowntimeFieldid != null) {
                    FOAopsDowntime = getLinkedticketInfo.fields[opsDowntimeFieldid]

                }

                //BUSINESS
                if (bizDowntimeFieldid != null) {
                    FOAbizDowntime = getLinkedticketInfo.fields[bizDowntimeFieldid]
                }

                if (FOAopsDowntime != null && FOAbizDowntime != null) {
                    SICtotalOpsDowntime += FOAopsDowntime as Float
                    SICtotalBizDowntime += FOAbizDowntime as Float
                }

                //LOG WARNING ABOUT NULL FIELDS
            } else {
                logger.warn(LinkedIssueKey + "The is an ERROR one of the following fields: DOWNTIME IMPACT, TIME OF Occurrence, TIME OF RETURN TO NOMINAL OPERATIONS.")

            } //end of NULL values check
            SICtotalIncidentCount += 1 //Count Number of FOA tickets
        }
    }
} //end of loop


//UPDATE the last occurrence  field on SIC TICKET

//Sort and get the last FOA Ticket Timestamp
String lastOccurrenceTimeStamp = null
latestFOAOccurance.sort()
latestFOAOccurance.each { println it }
if (!latestFOAOccurance.isEmpty()) {
    lastOccurrenceTimeStamp = latestFOAOccurance.last()
}

if (currentLatestOccurrence != lastOccurrenceTimeStamp) {
    SICUpdateLastOccurence(issueKey, lastOccurrenceFieldid, lastOccurrenceTimeStamp)
    logger.info("Updated latest occurrance " + lastOccurrenceTimeStamp)
}


//Update the Total Number of Customer Returns Filed on the SIC Ticket
SICUpdateCustomerReturns(issueKey, totalCustomerReturnsFieldid, totalCustomerReturns)


/***
 * TOTAL - update the SIC ticket  with the calculated downtime values
 */
//Create a Map of with values
def fieldUpdates_Total = ["${opsTotalDowntimeFieldid}"  : "${SICtotalOpsDowntime}",
                          "${bizTotalDowntimeFieldid}"  : "${SICtotalBizDowntime}",
                          "${totalIncidentCountFieldid}": "${SICtotalIncidentCount}"] as Map

//update SIC ticket
updateIssueFields(issueKey, fieldUpdates_Total)


/** ================================= HELPER METHODS ==================================================*/


//Update Issue fields from an array list of fields
def updateIssueFields(String issueKey, Map fieldsValues) {
    def getIssue = get("/rest/api/2/issue/${issueKey}").asObject(Map).body

    // `entry` is a map entry
    fieldsValues.each { entry ->
        def issueField = "$entry.key" as String
        def issueValue = "$entry.value" as float

        def getCurrentFieldValue = getIssue.fields[issueField]
//only update if the value has changed
        if (getCurrentFieldValue != issueValue) {

            def resp = put("/rest/api/2/issue/${issueKey}")
                    .header('Content-Type', 'application/json')
                    .body([
                            fields: [

                                    (issueField): issueValue
                            ]
                    ])
                    .asString()
            assert resp.status == 204
        } //end of if
    }
}


//Update the Primary root Cause field
def SICUpdateLastOccurence(String SICKey, String lastOccurrenceFieldid, String OccurrenceTimeStamp) {

    //Update the FOA ticket with the latest most updated FOA ticket
    def resp = put("/rest/api/2/issue/${SICKey}")
            .header('Content-Type', 'application/json')
            .body([
                    fields: [
                            (lastOccurrenceFieldid): OccurrenceTimeStamp
                    ]

            ])
            .asString()
    
    assert resp.status == 204
}


//Update Total Customer Returns Field
def SICUpdateCustomerReturns(String SICKey, String totalCustomerReturnFieldid, Double numberofReturns) {

    //Update the FOA ticket with the latest most updated FOA ticket
    def resp = put("/rest/api/2/issue/${SICKey}")
            .header('Content-Type', 'application/json')
            .body([
                    fields: [
                            (totalCustomerReturnFieldid): numberofReturns
                    ]

            ])
            .asString()
    assert resp.status == 204
}


//Get issue type ID, by Name
String getIssueTypeIdFromName(issueType) {
    def issueTypeObject = get('/rest/api/2/issuetype').asObject(List).body.find {
        (it as Map).name == issueType
    } as Map
    issueTypeObject.id
}