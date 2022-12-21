import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId



// get custom fields
def customFields = get("/rest/api/2/field")
        .asObject(List)
        .body
        .findAll { (it as Map).custom } as List<Map>


def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
def formatter2 = DateTimeFormatter.ofPattern("HH:mm z")


//Get the IDs of the Custom Fields
def timeOfOccuranceFieldid = customFields.find { it.name == 'Time of occurrence' }?.id
def displayTimeFieldid = customFields.find { it.name == 'Time of Day' }?.id

//Get the Value of the field
def timeOfOccuranceValue = issue.fields[timeOfOccuranceFieldid] as String

//Format Dates
def formatedTimeOfOccurance = ZonedDateTime.parse(timeOfOccuranceValue, formatter)
def utcTime = formatedTimeOfOccurance.withZoneSameInstant(ZoneId.of("UTC"))
def formattedHM = utcTime.format(formatter2) as String
def unixTimestamp = formatedTimeOfOccurance.toEpochSecond() as String
def combined = formattedHM + " - " + unixTimestamp

def resp = put("/rest/api/2/issue/${issue.key}")
        .header('Content-Type', 'application/json')
        .body([
        fields: [
                
                (displayTimeFieldid): combined
        ]
])
        .asString()
assert resp.status == 204
