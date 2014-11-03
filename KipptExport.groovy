@Grapes([
        @Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.7.2'),
        @Grab(group = 'com.fasterxml.jackson.core', module = 'jackson-databind', version = '2.4.3'),
        @Grab(group = 'ch.qos.logback', module = 'logback-classic', version = '1.0.13'),
        @Grab(group = 'org.codehaus.gpars', module = 'gpars', version = '1.2.1')
])

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import groovyx.net.http.AsyncHTTPBuilder
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.HttpResponseDecorator

import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

import static groovyx.gpars.GParsPool.withPool
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.TEXT

@Slf4j
class KipptExporter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()

    private final HTTPBuilder http
    private final String username
    private final String apiKey

    KipptExporter(String username, String apiKey) {
        http = new AsyncHTTPBuilder(
                poolSize: 10,
                uri: 'https://kippt.com/'
        )
        http.ignoreSSLIssues()

        this.username = username
        this.apiKey = apiKey
    }

    private void fetchData(String path, Closure successClosure, Closure failureClosure = {}) {
        log.debug("Fetching data from ${path}")
        http.request(GET, TEXT) { req ->
            uri.path = path
            uri.query = [limit: 200]
            headers.'X-Kippt-Username' = username
            headers.'X-Kippt-API-Token' = apiKey

            response.success = successClosure
            response.failure = failureClosure
        }
    }

    private void writeToDisk(Map<String, List<Map>> clipsByList, File destinationFile) {
        int clipCount = 0
        def builder = clipsByList.inject(new StringBuilder()) { StringBuilder builder, String list, List<Map> clips ->
            builder.append("<DT><H3>${list}</H3> <DL><p>")
            clips.each {
                builder.append("<DT><A HREF=\"${it.url}\">${it.title}</A>")
                clipCount++
            }
            builder.append("</DL><p>")
        }

        destinationFile.withWriter { BufferedWriter bufferedWriter ->
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

        log.info("Wrote ${clipCount} clips to ${destinationFile}")
    }

    private String expandUrl(String url, int timeout = 2500) {
        try {
            def connection = new URL(url).openConnection() as HttpURLConnection
            connection.setRequestMethod('HEAD')
            connection.setInstanceFollowRedirects(false)
            connection.setConnectTimeout(timeout)
            connection.setReadTimeout(timeout)
            connection.connect()

            def responseCode = connection.getResponseCode()
            switch (responseCode) {
                case 301..303:
                    return connection.getHeaderField('Location')

                case 404:
                    return null

                default:
                    return url
            }
        } catch (UnknownHostException e) {
            return null
        } catch (SSLHandshakeException e) {
            log.warn("Unable to expand url (${url}), SSL handshake failure.")
            return url
        } catch (SocketTimeoutException e) {
            log.warn("Unable to expand url (${url}), request timed out.")
            return url
        } catch (Exception e) {
            log.warn("Unable to expand url (${url})", e)
            return url
        }
    }

    void run() {
        def clipsByList = new ConcurrentHashMap<String, List<Map>>()
        clipsByList.put('Inbox', [])

        fetchData('/api/lists', { HttpResponseDecorator listResponse, Reader listReader ->
            def lists = OBJECT_MAPPER.readValue(listReader, Map).objects as List<Map>
            def listsLatch = new CountDownLatch(lists.size())
            def importedLinks = new HashSet<String>()

            withPool(5, {
                lists.sort { it.title }.eachParallel { Map listObject ->
                    clipsByList.putIfAbsent(listObject.title, [])

                    fetchData('/api/lists/' + listObject.id + '/clips', { HttpResponseDecorator resp, Reader clipReader ->
                        withPool(10, {
                            clipsByList[listObject.title] = OBJECT_MAPPER.readValue(clipReader, Map).objects.sort {
                                -it.created
                            }.collectParallel { Map clipObject ->
                                def url = expandUrl(clipObject.url.toString())
                                if (url && !importedLinks.contains(url.toUpperCase())) {
                                    importedLinks << url.toUpperCase()
                                    return [
                                            title: clipObject.title,
                                            url  : url
                                    ]
                                }
                                return null
                            }.findAll { it != null }
                            listsLatch.countDown()
                        })
                    }, {
                        // Kippt will return a 404 in the event of an empty list but the latch still needs decrementing.
                        listsLatch.countDown()
                    })
                }
            })

            listsLatch.await()
            writeToDisk(clipsByList, new File("kippt-export-${System.currentTimeMillis()}.html"))
            http.shutdown()
        })
    }
}

def cli = new CliBuilder(usage: 'groovy KipptExport.groovy [-h] -u <username> -a <api key>')

cli.h(longOpt: 'help', 'usage information', required: false)
cli.u(longOpt: 'user', 'Kippt username', required: true, args: 1)
cli.a(longOpt: 'apiKey', 'Kippt api key', required: true, args: 1)

OptionAccessor opt = cli.parse(args)
if (!opt) {
    return
}

new KipptExporter(opt.u as String, opt.a as String).run()