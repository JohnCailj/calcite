/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.server;

import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.jdbc.CalciteConnection;

/**
 * Server.
 * <p>Represents shared state among connections, and will have monitoring and
 * management facilities.
 */
public interface CalciteServer {

    void removeStatement(Meta.StatementHandle h);

    void addStatement(CalciteConnection connection, Meta.StatementHandle h);

    /**
     * Returns the statement with a given handle.
     *
     * @param h Statement handle
     * @return Statement, never null
     * @throws NoSuchStatementException if handle does not represent a statement
     */
    CalciteServerStatement getStatement(Meta.StatementHandle h) throws NoSuchStatementException;
}

// End CalciteServer.java
