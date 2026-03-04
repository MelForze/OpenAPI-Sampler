package burp.openapi;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.http.Http;
import burp.api.montoya.intruder.Intruder;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.repeater.Repeater;
import burp.api.montoya.scanner.Scanner;
import burp.api.montoya.scope.Scope;
import burp.api.montoya.sitemap.SiteMap;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;

import javax.swing.JPanel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class TestApiFactory
{
    private TestApiFactory()
    {
    }

    static ApiContext apiContext()
    {
        MontoyaApi api = mock(MontoyaApi.class);
        Extension extension = mock(Extension.class);
        UserInterface userInterface = mock(UserInterface.class);
        HttpRequestEditor requestEditor = mock(HttpRequestEditor.class);
        Logging logging = mock(Logging.class);
        Repeater repeater = mock(Repeater.class);
        Intruder intruder = mock(Intruder.class);
        Scope scope = mock(Scope.class);
        Http http = mock(Http.class);
        SiteMap siteMap = mock(SiteMap.class);
        Scanner scanner = mock(Scanner.class);

        Registration registration = mock(Registration.class);

        when(api.extension()).thenReturn(extension);
        when(api.userInterface()).thenReturn(userInterface);
        when(api.logging()).thenReturn(logging);
        when(api.repeater()).thenReturn(repeater);
        when(api.intruder()).thenReturn(intruder);
        when(api.scope()).thenReturn(scope);
        when(api.http()).thenReturn(http);
        when(api.siteMap()).thenReturn(siteMap);
        when(api.scanner()).thenReturn(scanner);

        when(userInterface.createHttpRequestEditor(any(EditorOptions[].class))).thenReturn(requestEditor);
        when(userInterface.registerSuiteTab(anyString(), any())).thenReturn(registration);
        when(userInterface.registerContextMenuItemsProvider(any())).thenReturn(registration);
        when(requestEditor.uiComponent()).thenReturn(new JPanel());

        return new ApiContext(
                api,
                extension,
                userInterface,
                requestEditor,
                logging,
                repeater,
                intruder,
                scope,
                http,
                siteMap,
                scanner
        );
    }

    static final class ApiContext
    {
        final MontoyaApi api;
        final Extension extension;
        final UserInterface userInterface;
        final HttpRequestEditor requestEditor;
        final Logging logging;
        final Repeater repeater;
        final Intruder intruder;
        final Scope scope;
        final Http http;
        final SiteMap siteMap;
        final Scanner scanner;

        private ApiContext(
                MontoyaApi api,
                Extension extension,
                UserInterface userInterface,
                HttpRequestEditor requestEditor,
                Logging logging,
                Repeater repeater,
                Intruder intruder,
                Scope scope,
                Http http,
                SiteMap siteMap,
                Scanner scanner)
        {
            this.api = api;
            this.extension = extension;
            this.userInterface = userInterface;
            this.requestEditor = requestEditor;
            this.logging = logging;
            this.repeater = repeater;
            this.intruder = intruder;
            this.scope = scope;
            this.http = http;
            this.siteMap = siteMap;
            this.scanner = scanner;
        }
    }
}
