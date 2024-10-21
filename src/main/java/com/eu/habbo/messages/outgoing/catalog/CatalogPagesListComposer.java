package com.eu.habbo.messages.outgoing.catalog;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.catalog.CatalogPage;
import com.eu.habbo.habbohotel.permissions.Permission;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class CatalogPagesListComposer extends MessageComposer {

    private final Habbo habbo;
    private final String mode;
    private final boolean hasPermission;

    public CatalogPagesListComposer(Habbo habbo, String mode) {
        this.habbo = habbo;
        this.mode = mode;
        this.hasPermission = this.habbo.hasRight(Permission.ACC_CATALOG_IDS);
    }

    @Override
    protected ServerMessage composeInternal() {
        try {
            List<CatalogPage> pages = Emulator.getGameEnvironment().getCatalogManager().getCatalogPages(-1, this.habbo);

            this.response.init(Outgoing.catalogPagesListComposer);

            this.response.appendBoolean(true);
            this.response.appendInt(0);
            this.response.appendInt(-1);
            this.response.appendString("root");
            this.response.appendString("");
            this.response.appendInt(0);
            this.response.appendInt(pages.size());

            for (CatalogPage category : pages) {
                this.append(category);
            }

            this.response.appendBoolean(false);
            this.response.appendString(this.mode);

            return this.response;
        } catch (Exception e) {
            log.error("Caught exception", e);
        }

        return null;
    }

    private void append(CatalogPage category) {
        List<CatalogPage> pagesList = Emulator.getGameEnvironment().getCatalogManager().getCatalogPages(category.getId(), this.habbo);

        this.response.appendBoolean(category.isVisible());
        this.response.appendInt(category.getIconImage());
        this.response.appendInt(category.isEnabled() ? category.getId() : -1);
        this.response.appendString(category.getPageName());
        this.response.appendString(category.getCaption() + (this.hasPermission ? " (" + category.getId() + ")" : ""));
        this.response.appendInt(category.getOfferIds().size());

        for (int i : category.getOfferIds().toArray()) {
            this.response.appendInt(i);
        }

        this.response.appendInt(pagesList.size());

        for (CatalogPage page : pagesList) {
            this.append(page);
        }
    }
}