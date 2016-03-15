/*
 *
 * This file is part of the XiPKI project.
 * Copyright (c) 2013 - 2016 Lijun Liao
 * Author: Lijun Liao
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License (version 3
 * or later at your option) as published by the Free Software Foundation
 * with the addition of the following permission added to Section 15 as
 * permitted in Section 7(a):
 * FOR ANY PART OF THE COVERED WORK IN WHICH THE COPYRIGHT IS OWNED BY
 * THE AUTHOR LIJUN LIAO. LIJUN LIAO DISCLAIMS THE WARRANTY OF NON INFRINGEMENT
 * OF THIRD PARTY RIGHTS.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * The interactive user interfaces in modified source and object code versions
 * of this program must display Appropriate Legal Notices, as required under
 * Section 5 of the GNU Affero General Public License.
 *
 * You can be released from the requirements of the license by purchasing
 * a commercial license. Buying such a license is mandatory as soon as you
 * develop commercial activities involving the XiPKI software without
 * disclosing the source code of your own applications.
 *
 * For more information, please contact Lijun Liao at this
 * address: lijun.liao@gmail.com
 */

package org.xipki.commons.security.impl.p11;

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.interfaces.RSAPublicKey;

import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.xipki.commons.common.util.ParamUtil;
import org.xipki.commons.security.api.p11.P11CryptService;
import org.xipki.commons.security.api.p11.P11EntityIdentifier;
import org.xipki.commons.security.api.p11.P11TokenException;

/**
 * @author Lijun Liao
 * @since 2.0.0
 */
// CHECKSTYLE:SKIP
public class P11RSAKeyParameter extends RSAKeyParameters {

    private final P11CryptService p11CryptService;

    private final P11EntityIdentifier entityId;

    private final int keysize;

    private P11RSAKeyParameter(
            final P11CryptService p11CryptService,
            final P11EntityIdentifier entityId,
            final BigInteger modulus,
            final BigInteger publicExponent) {
        super(true, modulus, publicExponent);

        ParamUtil.requireNonNull("modulus", modulus);
        ParamUtil.requireNonNull("publicExponent", publicExponent);
        this.p11CryptService = ParamUtil.requireNonNull("p11CryptService", p11CryptService);
        this.entityId = ParamUtil.requireNonNull("entityId", entityId);
        this.keysize = modulus.bitLength();
    }

    int getKeysize() {
        return keysize;
    }

    P11CryptService getP11CryptService() {
        return p11CryptService;
    }

    P11EntityIdentifier getEntityId() {
        return entityId;
    }

    public static P11RSAKeyParameter getInstance(
            final P11CryptService p11CryptService,
            final P11EntityIdentifier entityId)
    throws InvalidKeyException {
        ParamUtil.requireNonNull("p11CryptService", p11CryptService);
        ParamUtil.requireNonNull("entityId", entityId);

        RSAPublicKey key;
        try {
            key = (RSAPublicKey) p11CryptService.getPublicKey(entityId);
        } catch (P11TokenException ex) {
            throw new InvalidKeyException(ex.getMessage(), ex);
        }

        BigInteger modulus = key.getModulus();
        BigInteger publicExponent = key.getPublicExponent();
        return new P11RSAKeyParameter(p11CryptService, entityId, modulus, publicExponent);
    }

}
