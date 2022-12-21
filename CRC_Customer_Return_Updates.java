import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import groovy.xml.MarkupBuilder


/**
 This script Updates the Primary Root Cause field, which is the most recently added/linked SIC ticket
 Trigger: issue.issueType.name == "Customer Return"

 Deployment To-do:
 - Update with projectKey with Production project Key
 - Add Trigger: issue.issueType.name == "Customer Return"
 */


def issueKey = issue.key
def projectKey = 'CRC'    //UPDATED WITH PRODUCTION Project Key
// Get custom fields
def customFields = get("/rest/api/2/field")
        .asObject(List)
        .body
        .findAll {
            (it as Map).custom
        } as List<Map>


//Custom Fields
def primaryRootCauseFieldid = customFields.find { it.name == 'Primary Root Cause' }?.id

def thisIssue = get("/rest/api/2/issue/${issueKey}").asObject(Map).body
def thisIssueFields = thisIssue.find { it.key == "fields" }?.value as Map
def thisIssueLinks = thisIssueFields.find { it.key == "issuelinks" }?.value as Map


/**
 Update the Primary Root Cause Value from the Linked SIC tickets
 */

def primaryRootCauseTicket = []
//Iterate through all linked SIC tickets, linked reason "Causes"
for (int i = 0; i < thisIssueLinks.size(); i++) {
    def SICissue = thisIssueLinks.get(i) as Map
    //if cause is "Causes"
    if (SICissue.type.outward == "causes") {
        def SICissuekey = SICissue.inwardIssue.key as String //get SIC
        //only apply to sic  tickets
        if (SICissuekey.contains("SIC")) {
            primaryRootCauseTicket.add(SICissuekey)
        }
    }
}

/**
 Primary SIC ...the most recent
 */
if (!primaryRootCauseTicket.isEmpty()) {
    primaryRootCauseTicket.sort()
    String recentSICicket = primaryRootCauseTicket.last()
    //Update the Primary root Cause field on the FOA ticket
    CRC_UpdatePrimaryRootCause(issueKey, recentSICicket, primaryRootCauseFieldid)

}


/**
 * ================HELPER METHODS============================================================================
 * CRC_UpdatePrimaryRootCause - Updates the Primary Root Cause Field on the CRC Ticket
 */


//Update the Primary Root Cause (SIC Ticket)
def CRC_UpdatePrimaryRootCause(String FOAKey, String primaryTicketKey, String rootCauseFieldid) {
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