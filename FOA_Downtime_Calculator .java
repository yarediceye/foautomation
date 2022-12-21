import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 NAME: FOA_downtme_updates.groovy
 This script is triggered by the following
 - Issue Created
 - Issueink Deleted
 - Issue Created
 - Issue Updated
 - Option Issuelinks Changed

 Scope:
 FOA
 - Calculate Update Business Downtime and Operational Downtime Fields on FOA Tickets
 - Update the Primary Root Cause field for FOA Tickets
 SIC
 - Update the Latest Occurrence Field
 */


//Unit of time
final chronoUnit = ChronoUnit.MINUTES
final formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")


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


// Get custom fields
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


//SIC
def lastOccurrenceFieldid = customFields.find { it.name == 'Latest Occurrence' }?.id


//Both FOA and SIC
def sicGroupFieldid = customFields.find { it.name == 'SIC Group' }?.id


/**
 * 1. Update FOA ticket
 Business Downtime Impact
 Operational Downtime Impact
 Primary Root Cause
 */

def thisIssue = get("/rest/api/2/issue/${issueKey}").asObject(Map).body
def thisIssueFields = thisIssue.find { it.key == "fields" }?.value as Map
def thisIssueLinks = thisIssueFields.find { it.key == "issuelinks" }?.value as Map

//get field values
String downtimeImpactFieldvalue = thisIssue.fields[downtimeImpactFieldid]
String timeOfOccurrenceValue = thisIssue.fields[timeOfOccurrenceFieldid]
String timeofReturnToOpsValue = thisIssue.fields[timeReturnToOpsFieldid]
String createdDateValue = thisIssueFields.created


//Get and convert current Date and Time
def theDatetime = new Date()
def currentDateTime = theDatetime.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ") as String
def numdaysSinceCreated = chronoUnit.between(ZonedDateTime.parse(createdDateValue, formatter), ZonedDateTime.parse(currentDateTime, formatter)) / 60
numdaysSinceCreated = numdaysSinceCreated / 24

//If Time of Occurrence is NULL
if (timeOfOccurrenceValue == null) {
    timeOfOccurrenceValue = createdDateValue
}


//If time of Return to Nominal Operations is NULL
if (timeofReturnToOpsValue == null) {
    timeofReturnToOpsValue = currentDateTime
    if (numdaysSinceCreated >= 7) {
        timeofReturnToOpsValue = timeOfOccurrenceValue
    }
}

//CHECK IF any of these are NULL
if (downtimeImpactFieldvalue != null && timeofReturnToOpsValue >= timeOfOccurrenceValue) {
    def formatedTimeOfOccurrence = ZonedDateTime.parse(timeOfOccurrenceValue, formatter)
    def formatedReturnToOpsValue = ZonedDateTime.parse(timeofReturnToOpsValue, formatter)
    // Get Time difference
    def dateDifference = chronoUnit.between(formatedTimeOfOccurrence, formatedReturnToOpsValue) as Float
    def dateDiffHours = dateDifference / 60
    def BizDowntime = 0
    def OpsDowntime = 0

    //Operational ONLY
    if (downtimeImpactFieldvalue.contains("Operational Only")) {

        //1. UPDATE TOTAL
        BizDowntime = 0
        OpsDowntime = dateDiffHours
        def updateFields = ["${bizDowntimeFieldid}": "${BizDowntime}", "${opsDowntimeFieldid}": "${OpsDowntime}"] as Map
        updateIssueFields(issue.key, updateFields)

        // Business and Operational
    } else if (downtimeImpactFieldvalue.contains("Both Operational and Business")) {

        //1. UPDATE TOTAL
        BizDowntime = dateDiffHours
        OpsDowntime = dateDiffHours
        def updateFields = ["${bizDowntimeFieldid}": "${BizDowntime}", "${opsDowntimeFieldid}": "${OpsDowntime}"] as Map
        updateIssueFields(issue.key, updateFields)

    } else {

        BizDowntime = 0
        OpsDowntime = 0
        def updateFields = ["${bizDowntimeFieldid}": "${BizDowntime}", "${opsDowntimeFieldid}": "${OpsDowntime}"] as Map
        updateIssueFields(issue.key, updateFields)


    }
    //LOG WARNING ABOUT NULL FIELDS
} else {
    logger.warn("KEY: " + issueKey + "FIELD ERROR: DOWNTIME IMPACT, TIME OF Occurrence, TIME OF RETURN TO NOMINAL OPERATIONS.")

}


/**
 2. Update SIC ticket
 Latest Occurrence
 //================================================
 */

def primaryRootCauseTicket = []
//Iterate through all linked SIC tickets, linked reason "Causes"
for (int i = 0; i < thisIssueLinks.size(); i++) {
    def SICissue = thisIssueLinks.get(i) as Map
    //if cause is "Causes"
    if (SICissue.type.outward == "causes") {
        def SICissuekey = SICissue.inwardIssue.key as String //get SIC
        //only apply to sic  tickets
        if (SICissuekey.contains("SIC") || SICissuekey.contains("NCR")) {
            primaryRootCauseTicket.add(SICissuekey)
            def SICInfo = get("/rest/api/2/issue/${SICissuekey}").asObject(Map).body
            //Get value
            String lastOccurrenceValue = SICInfo.fields[lastOccurrenceFieldid]
            // setLatestOccurrence(SICissuekey, timeOfOccurrenceValue, lastOccurrenceFieldid, lastOccurrenceValue)
        }
    }
}


//Primary SIC ...the most recent, Largest Name
if (!primaryRootCauseTicket.isEmpty()) {
    primaryRootCauseTicket.sort()
    String recentSICicket = primaryRootCauseTicket.last()

    //Update the Primary root Cause field on the FOA ticket

    SICupdatePrimaryRootCause(issueKey, recentSICicket, primaryRootCauseFieldid)

    //Update the FA
    updateSIC_Category(issueKey, recentSICicket, sicGroupFieldid, primaryRootCauseFieldid)

}


/**
 ///////////////////////// ***** HELPER METHODS ***** /////////////////////////////////////////////////////////////////
 */

/**
 * UPDATE FIELDS, Taking an array input field id and value
 **/
def updateIssueFields(String issueKey, Map fieldsValues) {
    // `entry` is a map entry
    def thisIssue = get("/rest/api/2/issue/${issueKey}").asObject(Map).body

    fieldsValues.each { entry ->
        def issueField = "$entry.key" as String
        def issueValue = "$entry.value" as Float
        def currentFieldValue = thisIssue.fields[issueField]
        if (currentFieldValue != issueValue) {

            def resp = put("/rest/api/2/issue/${issueKey}")
                    .header('Content-Type', 'application/json')
                    .body([
                            fields: [
                                    (issueField): issueValue
                            ]
                    ])
                    .asString()
            assert resp.status == 204

        }//end of
    } //end of loop
} //end of Method


/**
 * Update the Primary Root Cause (SIC Ticket)
 */

def SICupdatePrimaryRootCause(String FOAKey, String primaryTicketKey, String rootCauseFieldid) {
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
}//end of method


/**
 * Update the Primary SIC Group field on the FOA ticket, that is based on the Primary Root Cause SIC Ticket
 */

def updateSIC_Category(String FOAKey, primarySicTicket, String sicGroupFieldid, String rootCauseFieldid) {


//Get the SIC Group field value from the SIC ticket, which is the primary root cause
    def sicIssue = get("/rest/api/2/issue/${primarySicTicket}").asObject(Map).body
    def sicIssueSICGroup = sicIssue.fields[sicGroupFieldid]


    //Compary the current value and assign it a new one if it NEW
    def thisIssue = get("/rest/api/2/issue/${FOAKey}").asObject(Map).body
    def currentsicGroupFieldid = thisIssue.fields[sicGroupFieldid]
    if (currentsicGroupFieldid != sicIssueSICGroup) {

        def resp = put("/rest/api/2/issue/${FOAKey}")
                .header('Content-Type', 'application/json')
                .body([
                        fields: [
                                (sicGroupFieldid): sicIssueSICGroup
                        ]

                ])
                .asString()
        assert resp.status == 204
    }//end of if
}


/**
 Determine and Calculate the Latest Occurrence FOA ticket
 */
def setLatestOccurrence(String SICKey, String FOAOccurrenceFieldid, String SICLatestOccurrenceFieldid, String SICLatestOccurrenceFieldValue) {

    // Get Issue information
    def SICIssue = get("/rest/api/2/issue/${SICKey}").asObject(Map).body

    def SICIssueFields = SICIssue.find { it.key == "fields" }?.value as Map
    def SICIssueLinks = SICIssueFields.find { it.key == "issuelinks" }?.value as Map
    def occurrenceArray = []

    //Iterate through all linkied FOA tickets, linked reason "Causes"
    for (int i = 0; i < SICIssueLinks.size(); i++) {
        def FOAissue = SICIssueLinks.get(i) as Map

        if (FOAissue.type.outward == "causes") {
            def FOAissuekey = FOAissue.outwardIssue.key as String // get FOA ticket key

            if ((FOAissuekey.contains("FOA-")) || (FOAissuekey.contains("BR-"))) {
                def getFOAticketInfo = get("/rest/api/2/issue/${FOAissuekey}").asObject(Map).body
                //Get the Value of the field
                def occurrenceTimeStamp = getFOAticketInfo.fields[FOAOccurrenceFieldid]
                System.out.println("time of occure--" + occurrenceTimeStamp)
                occurrenceArray.add(occurrenceTimeStamp)

            }
        }
    }

    if (!occurrenceArray.isEmpty()) {
        occurrenceArray.sort()
        String latestOccurrence = occurrenceArray.last()
        //check if existing value is the same, if so dont set it
        if (SICLatestOccurrenceFieldValue != latestOccurrence) {
            //Update the FOA ticket with the latest most updated FOA ticket


            def resp = put("/rest/api/2/issue/${SICKey}")
                    .header('Content-Type', 'application/json')
                    .body([
                            fields: [
                                    (SICLatestOccurrenceFieldid): latestOccurrence
                            ]
                    ])
                    .asString()
            assert resp.status == 204
        }
    }
}