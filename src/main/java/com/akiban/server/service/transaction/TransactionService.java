/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.server.service.transaction;

import com.akiban.server.service.Service;
import com.akiban.server.service.session.Session;

public interface TransactionService extends Service {
    interface Callback {
        void run(Session session, long timestamp);
    }

    interface CloseableTransaction extends AutoCloseable {
        void commit();
        void rollback();

        boolean commitOrRetry();

        @Override
        void close();
    }

    enum CallbackType {
        /** Invoked prior to calling commit. */
        PRE_COMMIT,
        /** Invoked <i>after</i> commit completes successfully. */
        COMMIT,
        /** Invoked <i>after</i> rollback completes successfully (but not for commit failure). */
        ROLLBACK,
        /** Invoked when the transaction ends, independent of success/failure of commit/rollback. */
        END
    }


    /** Returns true if there is a transaction active for the given Session */
    boolean isTransactionActive(Session session);

    /** Returns true if there is a transaction active for the given Session */
    boolean isRollbackPending(Session session);

    /** Returns the start timestamp for the open transaction. */
    long getTransactionStartTimestamp(Session session);

    /** Begin a new transaction. */
    void beginTransaction(Session session);

    /** Begin a new transaction that will rollback upon close if not committed. */
    CloseableTransaction beginCloseableTransaction(Session session);

    /** Commit the open transaction. */
    void commitTransaction(Session session);

    /** Rollback an open transaction. */
    void rollbackTransaction(Session session);

    /** Rollback the current transaction if open, otherwise do nothing. */
    void rollbackTransactionIfOpen(Session session);

    /** @return current step for the open transaction. */
    int getTransactionStep(Session session);

    /**
     * Sets the current step for the open transaction.
     * @return previous step value.
     */
    int setTransactionStep(Session session, int newStep);

    /**
     * Increments the current step for the open transaction.
     * @return previous step value.
     */
    int incrementTransactionStep(Session session);

    /** Add a callback to transaction. */
    void addCallback(Session session, CallbackType type, Callback callback);

    /** Add a callback to transaction that is required to be active. */
    void addCallbackOnActive(Session session, CallbackType type, Callback callback);

    /** Add a callback to transaction that is required to be inactive. */
    void addCallbackOnInactive(Session session, CallbackType type, Callback callback);
}
