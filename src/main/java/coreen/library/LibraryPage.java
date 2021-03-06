//
// $Id$

package coreen.library;

import com.google.gwt.core.client.GWT;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;

import com.threerings.gwt.ui.EnterClickAdapter;
import com.threerings.gwt.ui.FluentTable;
import com.threerings.gwt.ui.Widgets;
import com.threerings.gwt.util.DateUtil;

import coreen.client.AbstractPage;
import coreen.client.Args;
import coreen.client.Link;
import coreen.client.Page;
import coreen.icons.IconResources;
import coreen.model.Project;
import coreen.rpc.LibraryService;
import coreen.rpc.LibraryServiceAsync;
import coreen.ui.DataPanel;
import coreen.ui.SearchResultsPanel;
import coreen.ui.UIUtil;

/**
 * Displays all of the projects known to the system.
 */
public class LibraryPage extends AbstractPage
{
    public LibraryPage ()
    {
        initWidget(_binder.createAndBindUi(this));
        ClickHandler onSearch = new ClickHandler() {
            public void onClick (ClickEvent event) {
                String query = _search.getText().trim();
                if (query.equals("")) {
                    Link.go(Page.LIBRARY);
                } else {
                    Link.go(Page.LIBRARY, SEARCH, query);
                }
            }
        };
        _go.addClickHandler(onSearch);
        EnterClickAdapter.bind(_search, onSearch);
    }

    @Override // from AbstractPage
    public Page getId ()
    {
        return Page.LIBRARY;
    }

    @Override // from AbstractPage
    public void setArgs (Args args)
    {
        String action = args.get(0, "");
        if (action.equals(SEARCH)) {
            final String query = args.get(1, "").trim();
            _search.setText(query);
            UIUtil.setWindowTitle(query);
            _contents.setWidget(new SearchResultsPanel<LibraryService.SearchResult>() {
                /* ctor */ {
                    setQuery(query);
                    _libsvc.search(query, createCallback());
                }
                @Override protected void addResult (
                    FluentTable table, LibraryService.SearchResult result) {
                    table.add().setText(result.project, _styles.resultCell()).alignTop().
                        right().setWidget(createResultView(result), _styles.resultCell());
                }
            });
        } else {
            UIUtil.setWindowTitle("Coreen");
            _contents.setWidget(_projects);
        }
    }

    protected class ProjectsPanel extends DataPanel<Project[]>
    {
        public ProjectsPanel () {
            super("projects");
            _libsvc.getProjects(createCallback());
        }

        @Override // from DataPanel
            protected void init (Project[] data) {
            FluentTable table = new FluentTable(5, 0);
            table.add().setText("").
                right().setText("Project").
                right().setText("Path").
                right().setText("Last updated");
            table.getRowFormatter().addStyleName(0, _styles.listHeader());
            for (Project p : data) {
                table.add().setWidget(Widgets.makeActionImage(
                                          new Image(_icons.edit()), "Config...",
                                          Link.createHandler(Page.EDIT, p.id))).
                    right().setWidget(Link.create(p.name, Page.PROJECT, p.id)).
                    right().setText(p.rootPath).
                    right().setText(DateUtil.formatDateTime(p.lastUpdated));
            }
            if (data.length == 0) {
                table.add().setText("You have no projects. You should import some.");
            }
            add(table);
        }
    }

    protected interface Styles extends CssResource
    {
        String listHeader ();
    }
    protected @UiField Styles _styles;

    protected @UiField TextBox _search;
    protected @UiField Button _go;
    protected @UiField SimplePanel _contents;

    protected ProjectsPanel _projects = new ProjectsPanel();

    protected interface Binder extends UiBinder<Widget, LibraryPage> {}
    protected static final Binder _binder = GWT.create(Binder.class);

    protected static final String SEARCH = "search";

    protected static final LibraryServiceAsync _libsvc = GWT.create(LibraryService.class);
    protected static final IconResources _icons = GWT.create(IconResources.class);
}
