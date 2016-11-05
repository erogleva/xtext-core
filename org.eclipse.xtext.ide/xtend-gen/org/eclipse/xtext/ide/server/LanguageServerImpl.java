/**
 * Copyright (c) 2016 TypeFox GmbH (http://www.typefox.io) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.xtext.ide.server;

import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.emf.common.util.URI;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.eclipse.xtend.lib.annotations.Accessors;
import org.eclipse.xtend.lib.annotations.FinalFieldsConstructor;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.ide.server.Document;
import org.eclipse.xtext.ide.server.UriExtensions;
import org.eclipse.xtext.ide.server.WorkspaceManager;
import org.eclipse.xtext.ide.server.concurrent.CancellableIndicator;
import org.eclipse.xtext.ide.server.concurrent.RequestManager;
import org.eclipse.xtext.ide.server.contentassist.ContentAssistService;
import org.eclipse.xtext.ide.server.findReferences.WorkspaceResourceAccess;
import org.eclipse.xtext.ide.server.formatting.FormattingService;
import org.eclipse.xtext.ide.server.hover.HoverService;
import org.eclipse.xtext.ide.server.occurrences.IDocumentHighlightService;
import org.eclipse.xtext.ide.server.signatureHelp.SignatureHelpService;
import org.eclipse.xtext.ide.server.symbol.DocumentSymbolService;
import org.eclipse.xtext.ide.server.symbol.WorkspaceSymbolService;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.service.OperationCanceledManager;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.Issue;
import org.eclipse.xtext.xbase.lib.CollectionLiterals;
import org.eclipse.xtext.xbase.lib.Extension;
import org.eclipse.xtext.xbase.lib.Functions.Function1;
import org.eclipse.xtext.xbase.lib.Functions.Function2;
import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.eclipse.xtext.xbase.lib.ListExtensions;
import org.eclipse.xtext.xbase.lib.ObjectExtensions;
import org.eclipse.xtext.xbase.lib.Procedures.Procedure1;
import org.eclipse.xtext.xbase.lib.Procedures.Procedure2;
import org.eclipse.xtext.xbase.lib.Pure;

/**
 * @author Sven Efftinge - Initial contribution and API
 * @since 2.11
 */
@Accessors
@SuppressWarnings("all")
public class LanguageServerImpl implements LanguageServer, WorkspaceService, TextDocumentService, LanguageClientAware {
  @FinalFieldsConstructor
  public static class BufferedCancelIndicator implements CancelIndicator {
    private final CancelIndicator delegate;
    
    private Long canceledSince;
    
    @Override
    public boolean isCanceled() {
      if (((this.canceledSince == null) && this.delegate.isCanceled())) {
        long _currentTimeMillis = System.currentTimeMillis();
        this.canceledSince = Long.valueOf(_currentTimeMillis);
        return false;
      }
      return ((this.canceledSince != null) && (System.currentTimeMillis() > ((this.canceledSince).longValue() + 1000)));
    }
    
    public BufferedCancelIndicator(final CancelIndicator delegate) {
      super();
      this.delegate = delegate;
    }
  }
  
  @Inject
  private RequestManager requestManager;
  
  @Inject
  private WorkspaceSymbolService workspaceSymbolService;
  
  private InitializeParams params;
  
  @Inject
  private Provider<WorkspaceManager> workspaceManagerProvider;
  
  private WorkspaceManager workspaceManager;
  
  @Inject
  @Extension
  private UriExtensions _uriExtensions;
  
  @Inject
  @Extension
  private IResourceServiceProvider.Registry languagesRegistry;
  
  @Inject
  private OperationCanceledManager operationCanceledManager;
  
  @Override
  public CompletableFuture<InitializeResult> initialize(final InitializeParams params) {
    String _rootPath = params.getRootPath();
    boolean _tripleEquals = (_rootPath == null);
    if (_tripleEquals) {
      throw new IllegalArgumentException("Bad initialization request. rootPath must not be null.");
    }
    Map<String, Object> _extensionToFactoryMap = this.languagesRegistry.getExtensionToFactoryMap();
    boolean _isEmpty = _extensionToFactoryMap.isEmpty();
    if (_isEmpty) {
      throw new IllegalStateException("No Xtext languages have been registered. Please make sure you have added the languages\'s setup class in \'/META-INF/services/org.eclipse.xtext.ISetup\'");
    }
    this.params = params;
    WorkspaceManager _get = this.workspaceManagerProvider.get();
    this.workspaceManager = _get;
    WorkspaceResourceAccess _workspaceResourceAccess = new WorkspaceResourceAccess(this.workspaceManager);
    this.resourceAccess = _workspaceResourceAccess;
    final InitializeResult result = new InitializeResult();
    ServerCapabilities _serverCapabilities = new ServerCapabilities();
    final Procedure1<ServerCapabilities> _function = (ServerCapabilities it) -> {
      it.setHoverProvider(Boolean.valueOf(true));
      it.setDefinitionProvider(Boolean.valueOf(true));
      it.setReferencesProvider(Boolean.valueOf(true));
      it.setDocumentSymbolProvider(Boolean.valueOf(true));
      it.setWorkspaceSymbolProvider(Boolean.valueOf(true));
      SignatureHelpOptions _signatureHelpOptions = new SignatureHelpOptions(Collections.<String>unmodifiableList(CollectionLiterals.<String>newArrayList("(", ",")));
      it.setSignatureHelpProvider(_signatureHelpOptions);
      it.setTextDocumentSync(TextDocumentSyncKind.Incremental);
      CompletionOptions _completionOptions = new CompletionOptions();
      final Procedure1<CompletionOptions> _function_1 = (CompletionOptions it_1) -> {
        it_1.setResolveProvider(Boolean.valueOf(false));
        it_1.setTriggerCharacters(Collections.<String>unmodifiableList(CollectionLiterals.<String>newArrayList(".")));
      };
      CompletionOptions _doubleArrow = ObjectExtensions.<CompletionOptions>operator_doubleArrow(_completionOptions, _function_1);
      it.setCompletionProvider(_doubleArrow);
      it.setDocumentFormattingProvider(Boolean.valueOf(true));
      it.setDocumentRangeFormattingProvider(Boolean.valueOf(true));
      it.setDocumentHighlightProvider(Boolean.valueOf(true));
    };
    ServerCapabilities _doubleArrow = ObjectExtensions.<ServerCapabilities>operator_doubleArrow(_serverCapabilities, _function);
    result.setCapabilities(_doubleArrow);
    final Procedure1<CancelIndicator> _function_1 = (CancelIndicator cancelIndicator) -> {
      String _rootPath_1 = params.getRootPath();
      URI _createFileURI = URI.createFileURI(_rootPath_1);
      String _path = this._uriExtensions.toPath(_createFileURI);
      final URI rootURI = this._uriExtensions.toUri(_path);
      final Procedure2<URI, Iterable<Issue>> _function_2 = (URI $0, Iterable<Issue> $1) -> {
        this.publishDiagnostics($0, $1);
      };
      this.workspaceManager.initialize(rootURI, _function_2, cancelIndicator);
    };
    this.requestManager.runWrite(_function_1, CancellableIndicator.NullImpl);
    return CompletableFuture.<InitializeResult>completedFuture(result);
  }
  
  @Override
  public void connect(final LanguageClient client) {
    this.client = client;
  }
  
  @Override
  public void exit() {
  }
  
  @Override
  public CompletableFuture<Void> shutdown() {
    return null;
  }
  
  @Override
  public TextDocumentService getTextDocumentService() {
    return this;
  }
  
  @Override
  public WorkspaceService getWorkspaceService() {
    return this;
  }
  
  @Override
  public void didOpen(final DidOpenTextDocumentParams params) {
    final Procedure1<CancelIndicator> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentItem _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      URI _uri_1 = this._uriExtensions.toUri(_uri);
      TextDocumentItem _textDocument_1 = params.getTextDocument();
      int _version = _textDocument_1.getVersion();
      TextDocumentItem _textDocument_2 = params.getTextDocument();
      String _text = _textDocument_2.getText();
      this.workspaceManager.didOpen(_uri_1, _version, _text, cancelIndicator);
    };
    this.requestManager.runWrite(_function);
  }
  
  @Override
  public void didChange(final DidChangeTextDocumentParams params) {
    final Procedure1<CancelIndicator> _function = (CancelIndicator cancelIndicator) -> {
      VersionedTextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      URI _uri_1 = this._uriExtensions.toUri(_uri);
      VersionedTextDocumentIdentifier _textDocument_1 = params.getTextDocument();
      int _version = _textDocument_1.getVersion();
      List<TextDocumentContentChangeEvent> _contentChanges = params.getContentChanges();
      final Function1<TextDocumentContentChangeEvent, TextEdit> _function_1 = (TextDocumentContentChangeEvent event) -> {
        Range _range = event.getRange();
        String _text = event.getText();
        return new TextEdit(_range, _text);
      };
      List<TextEdit> _map = ListExtensions.<TextDocumentContentChangeEvent, TextEdit>map(_contentChanges, _function_1);
      this.workspaceManager.didChange(_uri_1, _version, _map, cancelIndicator);
    };
    this.requestManager.runWrite(_function);
  }
  
  @Override
  public void didClose(final DidCloseTextDocumentParams params) {
    final Procedure1<CancelIndicator> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      URI _uri_1 = this._uriExtensions.toUri(_uri);
      this.workspaceManager.didClose(_uri_1, cancelIndicator);
    };
    this.requestManager.runWrite(_function);
  }
  
  @Override
  public void didSave(final DidSaveTextDocumentParams params) {
  }
  
  @Override
  public void didChangeWatchedFiles(final DidChangeWatchedFilesParams params) {
    final Procedure1<CancelIndicator> _function = (CancelIndicator cancelIndicator) -> {
      final ArrayList<URI> dirtyFiles = CollectionLiterals.<URI>newArrayList();
      final ArrayList<URI> deletedFiles = CollectionLiterals.<URI>newArrayList();
      List<FileEvent> _changes = params.getChanges();
      for (final FileEvent fileEvent : _changes) {
        FileChangeType _type = fileEvent.getType();
        boolean _tripleEquals = (_type == FileChangeType.Deleted);
        if (_tripleEquals) {
          String _uri = fileEvent.getUri();
          URI _uri_1 = this._uriExtensions.toUri(_uri);
          deletedFiles.add(_uri_1);
        } else {
          String _uri_2 = fileEvent.getUri();
          URI _uri_3 = this._uriExtensions.toUri(_uri_2);
          dirtyFiles.add(_uri_3);
        }
      }
      this.workspaceManager.doBuild(dirtyFiles, deletedFiles, cancelIndicator);
    };
    this.requestManager.runWrite(_function);
  }
  
  @Override
  public void didChangeConfiguration(final DidChangeConfigurationParams params) {
    final Procedure1<CancelIndicator> _function = (CancelIndicator cancelIndicator) -> {
      this.workspaceManager.refreshWorkspaceConfig(cancelIndicator);
    };
    this.requestManager.runWrite(_function);
  }
  
  private WorkspaceResourceAccess resourceAccess;
  
  private LanguageClient client;
  
  private void publishDiagnostics(final URI uri, final Iterable<? extends Issue> issues) {
    PublishDiagnosticsParams _publishDiagnosticsParams = new PublishDiagnosticsParams();
    final Procedure1<PublishDiagnosticsParams> _function = (PublishDiagnosticsParams it) -> {
      String _path = this._uriExtensions.toPath(uri);
      it.setUri(_path);
      final Function1<Issue, Diagnostic> _function_1 = (Issue it_1) -> {
        return this.toDiagnostic(it_1);
      };
      Iterable<Diagnostic> _map = IterableExtensions.map(issues, _function_1);
      List<Diagnostic> _list = IterableExtensions.<Diagnostic>toList(_map);
      it.setDiagnostics(_list);
    };
    final PublishDiagnosticsParams diagnostics = ObjectExtensions.<PublishDiagnosticsParams>operator_doubleArrow(_publishDiagnosticsParams, _function);
    this.client.publishDiagnostics(diagnostics);
  }
  
  private Diagnostic toDiagnostic(final Issue issue) {
    Diagnostic _diagnostic = new Diagnostic();
    final Procedure1<Diagnostic> _function = (Diagnostic it) -> {
      String _code = issue.getCode();
      it.setCode(_code);
      DiagnosticSeverity _switchResult = null;
      Severity _severity = issue.getSeverity();
      if (_severity != null) {
        switch (_severity) {
          case ERROR:
            _switchResult = DiagnosticSeverity.Error;
            break;
          case WARNING:
            _switchResult = DiagnosticSeverity.Warning;
            break;
          case INFO:
            _switchResult = DiagnosticSeverity.Information;
            break;
          default:
            _switchResult = DiagnosticSeverity.Hint;
            break;
        }
      } else {
        _switchResult = DiagnosticSeverity.Hint;
      }
      it.setSeverity(_switchResult);
      String _message = issue.getMessage();
      it.setMessage(_message);
      Integer _elvis = null;
      Integer _lineNumber = issue.getLineNumber();
      if (_lineNumber != null) {
        _elvis = _lineNumber;
      } else {
        _elvis = Integer.valueOf(1);
      }
      final int lineNumber = ((_elvis).intValue() - 1);
      Integer _elvis_1 = null;
      Integer _column = issue.getColumn();
      if (_column != null) {
        _elvis_1 = _column;
      } else {
        _elvis_1 = Integer.valueOf(1);
      }
      final int column = ((_elvis_1).intValue() - 1);
      Integer _elvis_2 = null;
      Integer _length = issue.getLength();
      if (_length != null) {
        _elvis_2 = _length;
      } else {
        _elvis_2 = Integer.valueOf(0);
      }
      final Integer length = _elvis_2;
      Position _position = new Position(lineNumber, column);
      Position _position_1 = new Position(lineNumber, (column + (length).intValue()));
      Range _range = new Range(_position, _position_1);
      it.setRange(_range);
    };
    return ObjectExtensions.<Diagnostic>operator_doubleArrow(_diagnostic, _function);
  }
  
  @Override
  public CompletableFuture<CompletionList> completion(final TextDocumentPositionParams params) {
    final Function1<CancelIndicator, CompletionList> _function = (CancelIndicator origialCancelIndicator) -> {
      final LanguageServerImpl.BufferedCancelIndicator cancelIndicator = new LanguageServerImpl.BufferedCancelIndicator(origialCancelIndicator);
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider resourceServiceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      ContentAssistService _get = null;
      if (resourceServiceProvider!=null) {
        _get=resourceServiceProvider.<ContentAssistService>get(ContentAssistService.class);
      }
      final ContentAssistService contentAssistService = _get;
      if ((contentAssistService == null)) {
        return new CompletionList();
      }
      final Function2<Document, XtextResource, CompletionList> _function_1 = (Document document, XtextResource resource) -> {
        return contentAssistService.createCompletionList(document, resource, params, cancelIndicator);
      };
      final CompletionList result = this.workspaceManager.<CompletionList>doRead(uri, _function_1);
      return result;
    };
    return this.requestManager.<CompletionList>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends Location>> definition(final TextDocumentPositionParams params) {
    final Function1<CancelIndicator, List<? extends Location>> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider resourceServiceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      DocumentSymbolService _get = null;
      if (resourceServiceProvider!=null) {
        _get=resourceServiceProvider.<DocumentSymbolService>get(DocumentSymbolService.class);
      }
      final DocumentSymbolService documentSymbolService = _get;
      if ((documentSymbolService == null)) {
        return CollectionLiterals.<Location>emptyList();
      }
      final Function2<Document, XtextResource, List<? extends Location>> _function_1 = (Document document, XtextResource resource) -> {
        Position _position = params.getPosition();
        final int offset = document.getOffSet(_position);
        return documentSymbolService.getDefinitions(resource, offset, this.resourceAccess, cancelIndicator);
      };
      final List<? extends Location> definitions = this.workspaceManager.<List<? extends Location>>doRead(uri, _function_1);
      return definitions;
    };
    return this.requestManager.<List<? extends Location>>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends Location>> references(final ReferenceParams params) {
    final Function1<CancelIndicator, List<? extends Location>> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider resourceServiceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      DocumentSymbolService _get = null;
      if (resourceServiceProvider!=null) {
        _get=resourceServiceProvider.<DocumentSymbolService>get(DocumentSymbolService.class);
      }
      final DocumentSymbolService documentSymbolService = _get;
      if ((documentSymbolService == null)) {
        return CollectionLiterals.<Location>emptyList();
      }
      final Function2<Document, XtextResource, List<Location>> _function_1 = (Document document, XtextResource resource) -> {
        Position _position = params.getPosition();
        final int offset = document.getOffSet(_position);
        List<? extends Location> _xifexpression = null;
        ReferenceContext _context = params.getContext();
        boolean _isIncludeDeclaration = _context.isIncludeDeclaration();
        if (_isIncludeDeclaration) {
          _xifexpression = documentSymbolService.getDefinitions(resource, offset, this.resourceAccess, cancelIndicator);
        } else {
          _xifexpression = CollectionLiterals.emptyList();
        }
        final List<? extends Location> definitions = _xifexpression;
        final IResourceDescriptions indexData = this.workspaceManager.getIndex();
        final List<? extends Location> references = documentSymbolService.getReferences(resource, offset, this.resourceAccess, indexData, cancelIndicator);
        final Iterable<Location> result = Iterables.<Location>concat(definitions, references);
        return IterableExtensions.<Location>toList(result);
      };
      return this.workspaceManager.<List<Location>>doRead(uri, _function_1);
    };
    return this.requestManager.<List<? extends Location>>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(final DocumentSymbolParams params) {
    final Function1<CancelIndicator, List<? extends SymbolInformation>> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider resourceServiceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      DocumentSymbolService _get = null;
      if (resourceServiceProvider!=null) {
        _get=resourceServiceProvider.<DocumentSymbolService>get(DocumentSymbolService.class);
      }
      final DocumentSymbolService documentSymbolService = _get;
      if ((documentSymbolService == null)) {
        return CollectionLiterals.<SymbolInformation>emptyList();
      }
      final Function2<Document, XtextResource, List<? extends SymbolInformation>> _function_1 = (Document document, XtextResource resource) -> {
        return documentSymbolService.getSymbols(resource, cancelIndicator);
      };
      return this.workspaceManager.<List<? extends SymbolInformation>>doRead(uri, _function_1);
    };
    return this.requestManager.<List<? extends SymbolInformation>>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends SymbolInformation>> symbol(final WorkspaceSymbolParams params) {
    final Function1<CancelIndicator, List<? extends SymbolInformation>> _function = (CancelIndicator cancelIndicator) -> {
      final IResourceDescriptions indexData = this.workspaceManager.getIndex();
      String _query = params.getQuery();
      return this.workspaceSymbolService.getSymbols(_query, this.resourceAccess, indexData, cancelIndicator);
    };
    return this.requestManager.<List<? extends SymbolInformation>>runRead(_function);
  }
  
  @Override
  public CompletableFuture<Hover> hover(final TextDocumentPositionParams params) {
    final Function1<CancelIndicator, Hover> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider resourceServiceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      HoverService _get = null;
      if (resourceServiceProvider!=null) {
        _get=resourceServiceProvider.<HoverService>get(HoverService.class);
      }
      final HoverService hoverService = _get;
      if ((hoverService == null)) {
        List<String> _emptyList = CollectionLiterals.<String>emptyList();
        return new Hover(_emptyList, null);
      }
      final Function2<Document, XtextResource, Hover> _function_1 = (Document document, XtextResource resource) -> {
        Position _position = params.getPosition();
        final int offset = document.getOffSet(_position);
        return hoverService.hover(resource, offset);
      };
      return this.workspaceManager.<Hover>doRead(uri, _function_1);
    };
    return this.requestManager.<Hover>runRead(_function);
  }
  
  @Override
  public CompletableFuture<CompletionItem> resolveCompletionItem(final CompletionItem unresolved) {
    return CompletableFuture.<CompletionItem>completedFuture(unresolved);
  }
  
  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(final TextDocumentPositionParams position) {
    final Function1<CancelIndicator, SignatureHelp> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = position.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider serviceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      SignatureHelpService _get = null;
      if (serviceProvider!=null) {
        _get=serviceProvider.<SignatureHelpService>get(SignatureHelpService.class);
      }
      final SignatureHelpService helper = _get;
      if ((helper == null)) {
        return new SignatureHelp();
      }
      final Function2<Document, XtextResource, SignatureHelp> _function_1 = (Document doc, XtextResource resource) -> {
        Position _position = position.getPosition();
        final int offset = doc.getOffSet(_position);
        return helper.getSignatureHelp(resource, offset);
      };
      return this.workspaceManager.<SignatureHelp>doRead(uri, _function_1);
    };
    return this.requestManager.<SignatureHelp>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(final TextDocumentPositionParams position) {
    final Function1<CancelIndicator, List<? extends DocumentHighlight>> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = position.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider serviceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      IDocumentHighlightService _get = null;
      if (serviceProvider!=null) {
        _get=serviceProvider.<IDocumentHighlightService>get(IDocumentHighlightService.class);
      }
      final IDocumentHighlightService service = _get;
      if ((service == null)) {
        return CollectionLiterals.<DocumentHighlight>emptyList();
      }
      final Function2<Document, XtextResource, List<? extends DocumentHighlight>> _function_1 = (Document doc, XtextResource resource) -> {
        Position _position = position.getPosition();
        final int offset = doc.getOffSet(_position);
        return service.getDocumentHighlights(resource, offset);
      };
      return this.workspaceManager.<List<? extends DocumentHighlight>>doRead(uri, _function_1);
    };
    return this.requestManager.<List<? extends DocumentHighlight>>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends Command>> codeAction(final CodeActionParams params) {
    throw new UnsupportedOperationException("TODO: auto-generated method stub");
  }
  
  @Override
  public CompletableFuture<List<? extends CodeLens>> codeLens(final CodeLensParams params) {
    throw new UnsupportedOperationException("TODO: auto-generated method stub");
  }
  
  @Override
  public CompletableFuture<CodeLens> resolveCodeLens(final CodeLens unresolved) {
    return CompletableFuture.<CodeLens>completedFuture(unresolved);
  }
  
  @Override
  public CompletableFuture<List<? extends TextEdit>> formatting(final DocumentFormattingParams params) {
    final Function1<CancelIndicator, List<? extends TextEdit>> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider resourceServiceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      FormattingService _get = null;
      if (resourceServiceProvider!=null) {
        _get=resourceServiceProvider.<FormattingService>get(FormattingService.class);
      }
      final FormattingService formatterService = _get;
      if ((formatterService == null)) {
        return Collections.<TextEdit>emptyList();
      }
      final Function2<Document, XtextResource, List<TextEdit>> _function_1 = (Document document, XtextResource resource) -> {
        final int offset = 0;
        String _contents = document.getContents();
        final int length = _contents.length();
        if (((length == 0) || resource.getContents().isEmpty())) {
          return CollectionLiterals.<TextEdit>emptyList();
        }
        return formatterService.format(resource, document, offset, length);
      };
      return this.workspaceManager.<List<? extends TextEdit>>doRead(uri, _function_1);
    };
    return this.requestManager.<List<? extends TextEdit>>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends TextEdit>> rangeFormatting(final DocumentRangeFormattingParams params) {
    final Function1<CancelIndicator, List<? extends TextEdit>> _function = (CancelIndicator cancelIndicator) -> {
      TextDocumentIdentifier _textDocument = params.getTextDocument();
      String _uri = _textDocument.getUri();
      final URI uri = this._uriExtensions.toUri(_uri);
      final IResourceServiceProvider resourceServiceProvider = this.languagesRegistry.getResourceServiceProvider(uri);
      FormattingService _get = null;
      if (resourceServiceProvider!=null) {
        _get=resourceServiceProvider.<FormattingService>get(FormattingService.class);
      }
      final FormattingService formatterService = _get;
      if ((formatterService == null)) {
        return Collections.<TextEdit>emptyList();
      }
      final Function2<Document, XtextResource, List<TextEdit>> _function_1 = (Document document, XtextResource resource) -> {
        Range _range = params.getRange();
        Position _start = _range.getStart();
        final int offset = document.getOffSet(_start);
        Range _range_1 = params.getRange();
        Position _end = _range_1.getEnd();
        int _offSet = document.getOffSet(_end);
        final int length = (_offSet - offset);
        return formatterService.format(resource, document, offset, length);
      };
      return this.workspaceManager.<List<? extends TextEdit>>doRead(uri, _function_1);
    };
    return this.requestManager.<List<? extends TextEdit>>runRead(_function);
  }
  
  @Override
  public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(final DocumentOnTypeFormattingParams params) {
    throw new UnsupportedOperationException("TODO: auto-generated method stub");
  }
  
  @Override
  public CompletableFuture<WorkspaceEdit> rename(final RenameParams params) {
    throw new UnsupportedOperationException("TODO: auto-generated method stub");
  }
  
  @Pure
  public RequestManager getRequestManager() {
    return this.requestManager;
  }
  
  public void setRequestManager(final RequestManager requestManager) {
    this.requestManager = requestManager;
  }
  
  @Pure
  public WorkspaceSymbolService getWorkspaceSymbolService() {
    return this.workspaceSymbolService;
  }
  
  public void setWorkspaceSymbolService(final WorkspaceSymbolService workspaceSymbolService) {
    this.workspaceSymbolService = workspaceSymbolService;
  }
  
  @Pure
  public InitializeParams getParams() {
    return this.params;
  }
  
  public void setParams(final InitializeParams params) {
    this.params = params;
  }
  
  @Pure
  public Provider<WorkspaceManager> getWorkspaceManagerProvider() {
    return this.workspaceManagerProvider;
  }
  
  public void setWorkspaceManagerProvider(final Provider<WorkspaceManager> workspaceManagerProvider) {
    this.workspaceManagerProvider = workspaceManagerProvider;
  }
  
  @Pure
  public WorkspaceManager getWorkspaceManager() {
    return this.workspaceManager;
  }
  
  public void setWorkspaceManager(final WorkspaceManager workspaceManager) {
    this.workspaceManager = workspaceManager;
  }
  
  @Pure
  public UriExtensions get_uriExtensions() {
    return this._uriExtensions;
  }
  
  public void set_uriExtensions(final UriExtensions _uriExtensions) {
    this._uriExtensions = _uriExtensions;
  }
  
  @Pure
  public IResourceServiceProvider.Registry getLanguagesRegistry() {
    return this.languagesRegistry;
  }
  
  public void setLanguagesRegistry(final IResourceServiceProvider.Registry languagesRegistry) {
    this.languagesRegistry = languagesRegistry;
  }
  
  @Pure
  public OperationCanceledManager getOperationCanceledManager() {
    return this.operationCanceledManager;
  }
  
  public void setOperationCanceledManager(final OperationCanceledManager operationCanceledManager) {
    this.operationCanceledManager = operationCanceledManager;
  }
  
  @Pure
  public WorkspaceResourceAccess getResourceAccess() {
    return this.resourceAccess;
  }
  
  public void setResourceAccess(final WorkspaceResourceAccess resourceAccess) {
    this.resourceAccess = resourceAccess;
  }
  
  @Pure
  public LanguageClient getClient() {
    return this.client;
  }
  
  public void setClient(final LanguageClient client) {
    this.client = client;
  }
}
