This demo illustrates the basics of using [IceGrid][1], including the use of
[templates][2] and [object adapter replication][3].

To run the demo, first start the IceGrid service:

```
icegridnode --Ice.Config=config.grid
```

In a separate window:
```
icegridadmin --Ice.Config=config.grid -e "application add application.xml"
client
```

> With .NET 6, use instead:
> ```
> icegridadmin --Ice.Config=config.grid -e "application add application-net6.xml"
> dotnet client.dll
> ```

This will deploy the application and start the client. Messages will
be displayed in the IceGrid service window.

You can also use the descriptors in the following files to deploy the
application:

* `application_with_template.xml` and
  `application-net6_with_template.xml`: These descriptors
  demonstrate the use of templates for the server definition.
  Templates make it easy to deploy multiple instances of the same
  server.

* `application_with_replication.xml` and
  `application_with_replication.xml`: These descriptors demonstrate
  the use of replication to balance the load of the application over
  several servers.

If you have already deployed the application, you can update it to try
a new set of descriptors, for example:

```
icegridadmin --Ice.Config=config.grid -e "application update application_with_template.xml"
```

> With .NET 6, use instead the `-net6` XML files, for example:
> ```
> icegridadmin --Ice.Config=config.grid -e "application update application-net6_with_template.xml"
> ```

When using the descriptors from `application_with_template.xml`, or
`application_with_replication.xml`, you can easily deploy more servers
based on the `SimpleServer` template. For example, you can use the
following command to deploy a new server:

```
icegridadmin --Ice.Config=config.grid -e "server template instantiate Simple node1 SimpleServer index=4"
```

[1]: https://doc.zeroc.com/ice/3.7/ice-services/icegrid
[2]: https://doc.zeroc.com/ice/3.7/ice-services/icegrid/icegrid-templates
[3]: https://doc.zeroc.com/ice/3.7/ice-services/icegrid/object-adapter-replication
