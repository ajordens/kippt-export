import com.fasterxml.jackson.databind.ObjectMapper
@Grapes([
        @Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.7'),
        @Grab(group = 'com.fasterxml.jackson.core', module = 'jackson-databind', version = '2.4.3')
])

import groovyx.net.http.HTTPBuilder
import groovyx.net.http.HttpResponseDecorator

import java.util.concurrent.CountDownLatch

import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.TEXT

def cli = new CliBuilder(usage: 'groovy KipptExport.groovy [-h] -u <username> -a <api key>')

cli.h(longOpt: 'help', 'usage information', required: false)
cli.u(longOpt: 'user', 'Kippt username', required: true, args: 1)
cli.a(longOpt: 'apiKey', 'Kippt api key', required: true, args: 1)

OptionAccessor opt = cli.parse(args)
if (!opt) {
    return
}

def user = opt.u
def apiKey = opt.a
def http = new HTTPBuilder('https://kippt.com/')

def objectMapper = new ObjectMapper()
def clipsByList = ['Inbox': []]

def fetchData = { String path, Closure successClosure, Closure failureClosure = {} ->
    http.request(GET, TEXT) { req ->
        uri.path = path
        uri.query = [limit: 500]
        headers.'X-Kippt-Username' = user
        headers.'X-Kippt-API-Token' = apiKey

        response.success = successClosure
        response.failure = failureClosure
    }
}

def writeToDisk = {
    def builder = clipsByList.inject(new StringBuilder()) { StringBuilder builder, String list, List<Map> clips ->
        builder.append("<DT><H3>${list}</H3> <DL><p>")
        clips.each {
            builder.append("<DT><A HREF=\"${it.url}\">${it.title}</A>")
        }
        builder.append("</DL><p>")
    }

    new File("kippt-export-${System.currentTimeMillis()}.html").withWriter { BufferedWriter bufferedWriter ->
        bufferedWriter.write("""
<!DOCTYPE NETSCAPE-Bookmark-file-1>
<!-- This is an automatically generated file.
It will be read and overwritten.
DO NOT EDIT! -->
<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
<TITLE>Bookmarks</TITLE>
<H1>Bookmarks</H1>
<DL><p>
${builder.toString()}
</DL><p>
""".toString())
    }
}

fetchData.call('/api/lists', { HttpResponseDecorator listResponse, Reader listReader ->
    def lists = objectMapper.readValue(listReader, Map).objects as List<Map>
    def listsLatch = new CountDownLatch(lists.size())

    lists.sort { it.title }.each { Map listObject ->
        if (!clipsByList.containsKey(listObject.title)) {
            clipsByList[listObject.title] = []
        }

        fetchData.call('/api/lists/' + listObject.id + '/clips', { HttpResponseDecorator resp, Reader clipReader ->
            objectMapper.readValue(clipReader, Map).objects.sort { -it.created }.each { Map clipObject ->
                clipsByList[listObject.title] << [
                        title: clipObject.title,
                        url  : clipObject.url
                ]
            }

            listsLatch.countDown()
        }, {
            // Kippt will return a 404 in the event of an empty list but the latch still needs decrementing.
            listsLatch.countDown()
        })
    }

    listsLatch.await()
    writeToDisk.call()
})