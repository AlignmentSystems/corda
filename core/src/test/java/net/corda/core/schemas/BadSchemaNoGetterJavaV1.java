/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.core.schemas;

import javax.persistence.*;
import java.util.Arrays;

public class BadSchemaNoGetterJavaV1 extends MappedSchema {

    public BadSchemaNoGetterJavaV1() {
        super(TestJavaSchemaFamily.class, 1, Arrays.asList(State.class));
    }

    @Entity
    public static class State extends PersistentState {
        @JoinColumns({@JoinColumn(name = "itid"), @JoinColumn(name = "outid")})
        @OneToOne
        @MapsId
        public GoodSchemaJavaV1.State other;
        private String id;

        @Column
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
