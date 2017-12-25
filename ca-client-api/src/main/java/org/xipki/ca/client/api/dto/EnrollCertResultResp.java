/*
 *
 * Copyright (c) 2013 - 2018 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.ca.client.api.dto;

import java.util.ArrayList;
import java.util.List;

import org.bouncycastle.asn1.cmp.CMPCertificate;
import org.xipki.common.util.ParamUtil;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */

public class EnrollCertResultResp {

    private List<CMPCertificate> caCertificates;

    private List<ResultEntry> resultEntries;

    public EnrollCertResultResp() {
    }

    public void addCaCertificate(final CMPCertificate caCertificate) {
        if (caCertificates == null) {
            caCertificates = new ArrayList<>(1);
        }
        caCertificates.add(caCertificate);
    }

    public void addResultEntry(final ResultEntry resultEntry) {
        ParamUtil.requireNonNull("resultEntry", resultEntry);

        if (!(resultEntry instanceof EnrollCertResultEntry
                || resultEntry instanceof ErrorResultEntry)) {
            throw new IllegalArgumentException(
                    "Unaccepted parameter of class " + resultEntry.getClass().getName());
        }

        if (resultEntries == null) {
            resultEntries = new ArrayList<>(1);
        }

        resultEntries.add(resultEntry);
    }

    public List<CMPCertificate> caCertificates() {
        return caCertificates;
    }

    public List<ResultEntry> resultEntries() {
        return resultEntries;
    }

}
