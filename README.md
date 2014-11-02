Kippt Export
===

A super simple Groovy process to export links from Kippt and prepare them for import into the new Google Bookmarks
Manager.


Prerequisites
===

**JDK**

This project requires Groovy.


Quick Use
===

    $ groovy KipptExport.groovy -u ajordens -a 5a57112xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx004

Importing the resulting 'kippt-export-TIMESTAMP.html' into the Google Bookmarks Manager.


To Do
===

- De-duplicate URLs on export (ie. multiple Kippt entries for same link)
- Convert t.co links to their fully qualified equivalents.
- Support exporting nested groups of bookmarks from Kippt based on naming conventions.
- Pagination support (currently only import the first 200 links from each Kippt list).
- Avoid exporting links that have been previously imported in to the Google Bookmarks Manager.


Authors
===

Adam Jordens


Copyright and License
===

Copyright (C) 2014 Adam Jordens. Licensed under the MIT License.

See [LICENSE](https://raw.githubusercontent.com/ajordens/kippt-export/master/LICENSE) for more information.