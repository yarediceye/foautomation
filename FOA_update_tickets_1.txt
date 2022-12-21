// get custom fields
def customFields = get("/rest/api/2/field")
        .asObject(List)
        .body
        .findAll { (it as Map) } as List<Map>
        
def labelsFieldid = customFields.find { it.name == 'labels' }?.id as String 
//Add Total Number of tickets
Calendar cal=Calendar.getInstance();//it return same time as new Date()
def hour = cal.get(Calendar.HOUR_OF_DAY)
def day = cal.get(Calendar.DAY_OF_MONTH)
def base = day * 24 + hour

def startTicket = base * 15
def endTicket = startTicket + 14
logger.info(startTicket + " - " + endTicket)
def issuePrefix="FOA"
//Iterate through all linkied FOA tickets, linked reason "Causes"
for (int i = startTicket; i <=  endTicket; i++) {
def issueKey= issuePrefix+"-"+i
if (get("/rest/api/2/issue/${issueKey}").asObject(Map).body != null){
def getFOAticketInfo = get("/rest/api/2/issue/${issueKey}").asObject(Map).body 
def fields = getFOAticketInfo.find{ it.key == "fields" }?.value as Map
def labelValue=fields.labels  
def resp = put("/rest/api/2/issue/${issueKey}")
        .header('Content-Type', 'application/json')
        .body([
        fields: [
                
                labels        : ['scriptrunner']
        ]
])
        .asString()
assert resp.status == 204
//Replace the Label 
def resp2 = put("/rest/api/2/issue/${issueKey}")
        .header('Content-Type', 'application/json')
        .body([
        fields: [
                
                labels        : labelValue
        ]
])
        .asString()
assert resp2.status == 204
 
   }
}