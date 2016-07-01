/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.payment.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.killbill.automaton.OperationResult;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountInternalApi;
import org.killbill.billing.callcontext.InternalCallContext;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.invoice.api.InvoiceInternalApi;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.payment.api.DefaultPayment;
import org.killbill.billing.payment.api.DefaultPaymentAttempt;
import org.killbill.billing.payment.api.DefaultPaymentTransaction;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentAttempt;
import org.killbill.billing.payment.api.PaymentTransaction;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionStatus;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.core.janitor.IncompletePaymentTransactionTask;
import org.killbill.billing.payment.core.sm.PaymentAutomatonRunner;
import org.killbill.billing.payment.dao.PaymentAttemptModelDao;
import org.killbill.billing.payment.dao.PaymentDao;
import org.killbill.billing.payment.dao.PaymentModelDao;
import org.killbill.billing.payment.dao.PaymentTransactionModelDao;
import org.killbill.billing.payment.glue.DefaultPaymentService;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.payment.retry.DefaultRetryService;
import org.killbill.billing.payment.retry.PaymentRetryNotificationKey;
import org.killbill.billing.tag.TagInternalApi;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.entity.DefaultPagination;
import org.killbill.billing.util.entity.Pagination;
import org.killbill.billing.util.entity.dao.DefaultPaginationHelper.EntityPaginationBuilder;
import org.killbill.billing.util.entity.dao.DefaultPaginationHelper.SourcePaginationBuilder;
import org.killbill.clock.Clock;
import org.killbill.commons.locker.GlobalLocker;
import org.killbill.notificationq.api.NotificationEvent;
import org.killbill.notificationq.api.NotificationEventWithMetadata;
import org.killbill.notificationq.api.NotificationQueue;
import org.killbill.notificationq.api.NotificationQueueService;
import org.killbill.notificationq.api.NotificationQueueService.NoSuchNotificationQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

import static org.killbill.billing.util.entity.dao.DefaultPaginationHelper.getEntityPagination;
import static org.killbill.billing.util.entity.dao.DefaultPaginationHelper.getEntityPaginationFromPlugins;

public class PaymentProcessor extends ProcessorBase {

    private static final ImmutableList<PluginProperty> PLUGIN_PROPERTIES = ImmutableList.<PluginProperty>of();

    private final PaymentAutomatonRunner paymentAutomatonRunner;
    private final IncompletePaymentTransactionTask incompletePaymentTransactionTask;
    private final NotificationQueueService notificationQueueService;

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    public static final String SCHEDULED = "SCHEDULED";


    @Inject
    public PaymentProcessor(final OSGIServiceRegistration<PaymentPluginApi> pluginRegistry,
                            final AccountInternalApi accountUserApi,
                            final InvoiceInternalApi invoiceApi,
                            final TagInternalApi tagUserApi,
                            final PaymentDao paymentDao,
                            final InternalCallContextFactory internalCallContextFactory,
                            final GlobalLocker locker,
                            final PaymentAutomatonRunner paymentAutomatonRunner,
                            final IncompletePaymentTransactionTask incompletePaymentTransactionTask,
                            final NotificationQueueService notificationQueueService,
                            final Clock clock) {
        super(pluginRegistry, accountUserApi, paymentDao, tagUserApi, locker, internalCallContextFactory, invoiceApi, clock);
        this.paymentAutomatonRunner = paymentAutomatonRunner;
        this.incompletePaymentTransactionTask = incompletePaymentTransactionTask;
        this.notificationQueueService = notificationQueueService;
    }

    public Payment createAuthorization(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, @Nullable final UUID paymentMethodId, @Nullable final UUID paymentId, final BigDecimal amount, final Currency currency,
                                       @Nullable final String paymentExternalKey, @Nullable final String paymentTransactionExternalKey, final boolean shouldLockAccountAndDispatch,
                                       final Iterable<PluginProperty> properties, final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.AUTHORIZE, account, paymentMethodId, paymentId, null, amount, currency, paymentExternalKey, paymentTransactionExternalKey, shouldLockAccountAndDispatch, null, properties, callContext, internalCallContext);
    }

    public Payment createCapture(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, final UUID paymentId, final BigDecimal amount, final Currency currency,
                                 @Nullable final String paymentTransactionExternalKey, final boolean shouldLockAccountAndDispatch,
                                 final Iterable<PluginProperty> properties, final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.CAPTURE, account, null, paymentId, null, amount, currency, null, paymentTransactionExternalKey, shouldLockAccountAndDispatch, null, properties, callContext, internalCallContext);
    }

    public Payment createPurchase(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, @Nullable final UUID paymentMethodId, @Nullable final UUID paymentId, final BigDecimal amount, final Currency currency,
                                  @Nullable final String paymentExternalKey, @Nullable final String paymentTransactionExternalKey, final boolean shouldLockAccountAndDispatch,
                                  final Iterable<PluginProperty> properties, final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.PURCHASE, account, paymentMethodId, paymentId, null, amount, currency, paymentExternalKey, paymentTransactionExternalKey, shouldLockAccountAndDispatch, null, properties, callContext, internalCallContext);
    }

    public Payment createVoid(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, final UUID paymentId, @Nullable final String paymentTransactionExternalKey, final boolean shouldLockAccountAndDispatch,
                              final Iterable<PluginProperty> properties, final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.VOID, account, null, paymentId, null, null, null, null, paymentTransactionExternalKey, shouldLockAccountAndDispatch, null, properties, callContext, internalCallContext);
    }

    public Payment createRefund(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, final UUID paymentId, final BigDecimal amount, final Currency currency,
                                final String paymentTransactionExternalKey, final boolean shouldLockAccountAndDispatch,
                                final Iterable<PluginProperty> properties, final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.REFUND, account, null, paymentId, null, amount, currency, null, paymentTransactionExternalKey, shouldLockAccountAndDispatch, null, properties, callContext, internalCallContext);
    }

    public Payment createCredit(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, @Nullable final UUID paymentMethodId, @Nullable final UUID paymentId, final BigDecimal amount, final Currency currency,
                                @Nullable final String paymentExternalKey, @Nullable final String paymentTransactionExternalKey, final boolean shouldLockAccountAndDispatch,
                                final Iterable<PluginProperty> properties, final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.CREDIT, account, paymentMethodId, paymentId, null, amount, currency, paymentExternalKey, paymentTransactionExternalKey, shouldLockAccountAndDispatch, null, properties, callContext, internalCallContext);
    }

    public Payment createChargeback(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, final UUID paymentId, @Nullable final String paymentTransactionExternalKey, final BigDecimal amount, final Currency currency, final boolean shouldLockAccountAndDispatch,
                                    final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.CHARGEBACK, account, null, paymentId, null, amount, currency, null, paymentTransactionExternalKey, shouldLockAccountAndDispatch, null, PLUGIN_PROPERTIES, callContext, internalCallContext);
    }

    public Payment createChargebackReversal(final boolean isApiPayment, @Nullable final UUID attemptId, final Account account, final UUID paymentId, @Nullable final String paymentTransactionExternalKey, final BigDecimal amount, final Currency currency, final boolean shouldLockAccountAndDispatch,
                                    final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        return performOperation(isApiPayment, attemptId, TransactionType.CHARGEBACK, account, null, paymentId, null, amount, currency, null, paymentTransactionExternalKey, shouldLockAccountAndDispatch, OperationResult.FAILURE, PLUGIN_PROPERTIES, callContext, internalCallContext);
    }

    public Payment notifyPendingPaymentOfStateChanged(final Account account, final UUID transactionId, final boolean isSuccess, final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        final PaymentTransactionModelDao transactionModelDao = paymentDao.getPaymentTransaction(transactionId, internalCallContext);
        if (transactionModelDao.getTransactionStatus() != TransactionStatus.PENDING) {
            throw new PaymentApiException(ErrorCode.PAYMENT_NO_SUCH_SUCCESS_PAYMENT, transactionModelDao.getPaymentId());
        }

        final OperationResult overridePluginResult = isSuccess ? OperationResult.SUCCESS : OperationResult.FAILURE;

        return performOperation(true, null, transactionModelDao.getTransactionType(), account, null, transactionModelDao.getPaymentId(),
                                transactionModelDao.getId(), transactionModelDao.getAmount(), transactionModelDao.getCurrency(), null, transactionModelDao.getTransactionExternalKey(), true,
                                overridePluginResult, PLUGIN_PROPERTIES, callContext, internalCallContext);
    }

    public List<Payment> getAccountPayments(final UUID accountId, final boolean withPluginInfo, final TenantContext context, final InternalTenantContext tenantContext) throws PaymentApiException {
        final List<PaymentModelDao> paymentsModelDao = paymentDao.getPaymentsForAccount(accountId, tenantContext);
        final List<PaymentTransactionModelDao> transactionsModelDao = paymentDao.getTransactionsForAccount(accountId, tenantContext);

        final Map<UUID, PaymentPluginApi> paymentPluginByPaymentMethodId = new HashMap<UUID, PaymentPluginApi>();
        final Collection<UUID> absentPlugins = new HashSet<UUID>();
        final List<Payment> transformedPayments = Lists.<PaymentModelDao, Payment>transform(paymentsModelDao,
                                                                                            new Function<PaymentModelDao, Payment>() {
                                                                                                @Override
                                                                                                public Payment apply(final PaymentModelDao paymentModelDao) {
                                                                                                    List<PaymentTransactionInfoPlugin> pluginInfo = null;

                                                                                                    if (withPluginInfo) {
                                                                                                        PaymentPluginApi pluginApi = paymentPluginByPaymentMethodId.get(paymentModelDao.getPaymentMethodId());
                                                                                                        if (pluginApi == null && !absentPlugins.contains(paymentModelDao.getPaymentMethodId())) {
                                                                                                            try {
                                                                                                                pluginApi = getPaymentProviderPlugin(paymentModelDao.getPaymentMethodId(), tenantContext);
                                                                                                                paymentPluginByPaymentMethodId.put(paymentModelDao.getPaymentMethodId(), pluginApi);
                                                                                                            } catch (final PaymentApiException e) {
                                                                                                                log.warn("Unable to retrieve pluginApi for payment method " + paymentModelDao.getPaymentMethodId());
                                                                                                                absentPlugins.add(paymentModelDao.getPaymentMethodId());
                                                                                                            }
                                                                                                        }

                                                                                                        pluginInfo = getPaymentTransactionInfoPluginsIfNeeded(pluginApi, paymentModelDao, context);
                                                                                                    }

                                                                                                    return toPayment(paymentModelDao, transactionsModelDao, pluginInfo, false, tenantContext);
                                                                                                }
                                                                                            });

        // Copy the transformed list, so the transformation function is applied once (otherwise, the Janitor could be invoked multiple times)
        return ImmutableList.<Payment>copyOf(transformedPayments);
    }

    public Payment getPayment(final UUID paymentId, final boolean withPluginInfo, final boolean withAttempts, final Iterable<PluginProperty> properties, final TenantContext tenantContext, final InternalTenantContext internalTenantContext) throws PaymentApiException {
        final PaymentModelDao paymentModelDao = paymentDao.getPayment(paymentId, internalTenantContext);
        if (paymentModelDao == null) {
            return null;
        }
        return toPayment(paymentModelDao, withPluginInfo, withAttempts, properties, tenantContext, internalTenantContext);
    }

    public Payment getPaymentByExternalKey(final String paymentExternalKey, final boolean withPluginInfo, final Iterable<PluginProperty> properties, final TenantContext tenantContext, final InternalTenantContext internalTenantContext) throws PaymentApiException {
        final PaymentModelDao paymentModelDao = paymentDao.getPaymentByExternalKey(paymentExternalKey, internalTenantContext);
        if (paymentModelDao == null) {
            return null;
        }
        return toPayment(paymentModelDao, withPluginInfo, false, properties, tenantContext, internalTenantContext);
    }

    public Pagination<Payment> getPayments(final Long offset, final Long limit, final boolean withPluginInfo, final Iterable<PluginProperty> properties,
                                           final TenantContext tenantContext, final InternalTenantContext internalTenantContext) {
        return getEntityPaginationFromPlugins(true,
                                              getAvailablePlugins(),
                                              offset,
                                              limit,
                                              new EntityPaginationBuilder<Payment, PaymentApiException>() {
                                                  @Override
                                                  public Pagination<Payment> build(final Long offset, final Long limit, final String pluginName) throws PaymentApiException {
                                                      return getPayments(offset, limit, pluginName, withPluginInfo, properties, tenantContext, internalTenantContext);
                                                  }
                                              }
                                             );
    }

    public Pagination<Payment> getPayments(final Long offset, final Long limit, final String pluginName, final boolean withPluginInfo, final Iterable<PluginProperty> properties, final TenantContext tenantContext, final InternalTenantContext internalTenantContext) throws PaymentApiException {
        final PaymentPluginApi pluginApi = withPluginInfo ? getPaymentPluginApi(pluginName) : null;

        return getEntityPagination(limit,
                                   new SourcePaginationBuilder<PaymentModelDao, PaymentApiException>() {
                                       @Override
                                       public Pagination<PaymentModelDao> build() {
                                           // Find all payments for all accounts
                                           return paymentDao.getPayments(pluginName, offset, limit, internalTenantContext);
                                       }
                                   },
                                   new Function<PaymentModelDao, Payment>() {
                                       @Override
                                       public Payment apply(final PaymentModelDao paymentModelDao) {
                                           final List<PaymentTransactionInfoPlugin> pluginInfo = getPaymentTransactionInfoPluginsIfNeeded(pluginApi, paymentModelDao, tenantContext);
                                           return toPayment(paymentModelDao.getId(), pluginInfo, internalTenantContext);
                                       }
                                   }
                                  );
    }

    public Pagination<Payment> searchPayments(final String searchKey, final Long offset, final Long limit, final boolean withPluginInfo, final Iterable<PluginProperty> properties, final TenantContext tenantContext, final InternalTenantContext internalTenantContext) {
        if (withPluginInfo) {
            return getEntityPaginationFromPlugins(false,
                                                  getAvailablePlugins(),
                                                  offset,
                                                  limit,
                                                  new EntityPaginationBuilder<Payment, PaymentApiException>() {
                                                      @Override
                                                      public Pagination<Payment> build(final Long offset, final Long limit, final String pluginName) throws PaymentApiException {
                                                          return searchPayments(searchKey, offset, limit, pluginName, withPluginInfo, properties, tenantContext, internalTenantContext);
                                                      }
                                                  }
                                                 );
        } else {
            try {
                return getEntityPagination(limit,
                                           new SourcePaginationBuilder<PaymentModelDao, PaymentApiException>() {
                                               @Override
                                               public Pagination<PaymentModelDao> build() {
                                                   return paymentDao.searchPayments(searchKey, offset, limit, internalTenantContext);
                                               }
                                           },
                                           new Function<PaymentModelDao, Payment>() {
                                               @Override
                                               public Payment apply(final PaymentModelDao paymentModelDao) {
                                                   return toPayment(paymentModelDao.getId(), null, internalTenantContext);
                                               }
                                           }
                                          );
            } catch (final PaymentApiException e) {
                log.warn("Unable to search through payments", e);
                return new DefaultPagination<Payment>(offset, limit, null, null, Iterators.<Payment>emptyIterator());
            }
        }
    }

    public Pagination<Payment> searchPayments(final String searchKey, final Long offset, final Long limit, final String pluginName, final boolean withPluginInfo, final Iterable<PluginProperty> properties, final TenantContext tenantContext, final InternalTenantContext internalTenantContext) throws PaymentApiException {
        final PaymentPluginApi pluginApi = getPaymentPluginApi(pluginName);

        return getEntityPagination(limit,
                                   new SourcePaginationBuilder<PaymentTransactionInfoPlugin, PaymentApiException>() {
                                       @Override
                                       public Pagination<PaymentTransactionInfoPlugin> build() throws PaymentApiException {
                                           try {
                                               return pluginApi.searchPayments(searchKey, offset, limit, properties, tenantContext);
                                           } catch (final PaymentPluginApiException e) {
                                               throw new PaymentApiException(e, ErrorCode.PAYMENT_PLUGIN_SEARCH_PAYMENTS, pluginName, searchKey);
                                           }
                                       }

                                   },
                                   new Function<PaymentTransactionInfoPlugin, Payment>() {
                                       final List<PaymentTransactionInfoPlugin> cachedPaymentTransactions = new LinkedList<PaymentTransactionInfoPlugin>();

                                       @Override
                                       public Payment apply(final PaymentTransactionInfoPlugin pluginTransaction) {
                                           if (pluginTransaction.getKbPaymentId() == null) {
                                               // Garbage from the plugin?
                                               log.debug("Plugin {} returned a payment without a kbPaymentId for searchKey {}", pluginName, searchKey);
                                               return null;
                                           }

                                           if (cachedPaymentTransactions.isEmpty() ||
                                               (cachedPaymentTransactions.get(0).getKbPaymentId().equals(pluginTransaction.getKbPaymentId()))) {
                                               cachedPaymentTransactions.add(pluginTransaction);
                                               return null;
                                           } else {
                                               final Payment result = toPayment(pluginTransaction.getKbPaymentId(), withPluginInfo ? ImmutableList.<PaymentTransactionInfoPlugin>copyOf(cachedPaymentTransactions) : ImmutableList.<PaymentTransactionInfoPlugin>of(), internalTenantContext);
                                               cachedPaymentTransactions.clear();
                                               cachedPaymentTransactions.add(pluginTransaction);
                                               return result;
                                           }
                                       }
                                   }
                                  );
    }

    private Payment performOperation(final boolean isApiPayment, @Nullable final UUID attemptId,
                                     final TransactionType transactionType, final Account account,
                                     @Nullable final UUID paymentMethodId, @Nullable final UUID paymentId, @Nullable final UUID transactionId,
                                     @Nullable final BigDecimal amount, @Nullable final Currency currency,
                                     @Nullable final String paymentExternalKey, @Nullable final String paymentTransactionExternalKey,
                                     final boolean shouldLockAccountAndDispatch, @Nullable final OperationResult overridePluginOperationResult,
                                     final Iterable<PluginProperty> properties,
                                     final CallContext callContext, final InternalCallContext internalCallContext) throws PaymentApiException {
        final UUID nonNullPaymentId = paymentAutomatonRunner.run(isApiPayment,
                                                                 transactionType,
                                                                 account,
                                                                 attemptId,
                                                                 paymentMethodId,
                                                                 paymentId,
                                                                 transactionId,
                                                                 paymentExternalKey,
                                                                 paymentTransactionExternalKey,
                                                                 amount,
                                                                 currency,
                                                                 shouldLockAccountAndDispatch,
                                                                 overridePluginOperationResult,
                                                                 properties,
                                                                 callContext,
                                                                 internalCallContext);
        return getPayment(nonNullPaymentId, true, false, properties, callContext, internalCallContext);
    }

    // Used in bulk get API (getAccountPayments / getPayments)
    private List<PaymentTransactionInfoPlugin> getPaymentTransactionInfoPluginsIfNeeded(@Nullable final PaymentPluginApi pluginApi, final PaymentModelDao paymentModelDao, final TenantContext context) {
        if (pluginApi == null) {
            return null;
        }

        try {
            return getPaymentTransactionInfoPlugins(pluginApi, paymentModelDao, PLUGIN_PROPERTIES, context);
        } catch (final PaymentApiException e) {
            log.warn("Unable to retrieve plugin info for payment " + paymentModelDao.getId());
            return null;
        }
    }

    private List<PaymentTransactionInfoPlugin> getPaymentTransactionInfoPlugins(final PaymentPluginApi plugin, final PaymentModelDao paymentModelDao, final Iterable<PluginProperty> properties, final TenantContext context) throws PaymentApiException {
        try {
            return plugin.getPaymentInfo(paymentModelDao.getAccountId(), paymentModelDao.getId(), properties, context);
        } catch (final PaymentPluginApiException e) {
            throw new PaymentApiException(e, ErrorCode.PAYMENT_PLUGIN_GET_PAYMENT_INFO, paymentModelDao.getId(), e.toString());
        }
    }

    // Used in bulk get APIs (getPayments / searchPayments)
    private Payment toPayment(final UUID paymentId, @Nullable final Iterable<PaymentTransactionInfoPlugin> pluginTransactions, final InternalTenantContext tenantContext) {
        final PaymentModelDao paymentModelDao = paymentDao.getPayment(paymentId, tenantContext);
        if (paymentModelDao == null) {
            log.warn("Unable to find payment id " + paymentId);
            return null;
        }

        return toPayment(paymentModelDao, pluginTransactions, false, tenantContext);
    }

    // Used in single get APIs (getPayment / getPaymentByExternalKey)
    private Payment toPayment(final PaymentModelDao paymentModelDao, final boolean withPluginInfo, final boolean withAttempts, final Iterable<PluginProperty> properties, final TenantContext context, final InternalTenantContext tenantContext) throws PaymentApiException {
        final PaymentPluginApi plugin = getPaymentProviderPlugin(paymentModelDao.getPaymentMethodId(), tenantContext);
        final List<PaymentTransactionInfoPlugin> pluginTransactions = withPluginInfo ? getPaymentTransactionInfoPlugins(plugin, paymentModelDao, properties, context) : null;

        return toPayment(paymentModelDao, pluginTransactions, withAttempts, tenantContext);
    }

    private Payment toPayment(final PaymentModelDao paymentModelDao, @Nullable final Iterable<PaymentTransactionInfoPlugin> pluginTransactions,
                              final boolean withAttempts, final InternalTenantContext tenantContext) {
        final InternalTenantContext tenantContextWithAccountRecordId = getInternalTenantContextWithAccountRecordId(paymentModelDao.getAccountId(), tenantContext);
        final List<PaymentTransactionModelDao> transactionsForPayment = paymentDao.getTransactionsForPayment(paymentModelDao.getId(), tenantContextWithAccountRecordId);

        return toPayment(paymentModelDao, transactionsForPayment, pluginTransactions, withAttempts, tenantContextWithAccountRecordId);
    }

    // Used in bulk get API (getAccountPayments)
    private Payment toPayment(final PaymentModelDao curPaymentModelDao, final Iterable<PaymentTransactionModelDao> curTransactionsModelDao, @Nullable final Iterable<PaymentTransactionInfoPlugin> pluginTransactions, final boolean withAttempts, final InternalTenantContext internalTenantContext) {
        final Ordering<PaymentTransaction> perPaymentTransactionOrdering = Ordering.<PaymentTransaction>from(new Comparator<PaymentTransaction>() {
            @Override
            public int compare(final PaymentTransaction o1, final PaymentTransaction o2) {
                return o1.getEffectiveDate().compareTo(o2.getEffectiveDate());
            }
        });

        // Need to filter for optimized codepaths looking up by account_record_id
        final Iterable<PaymentTransactionModelDao> filteredTransactions = Iterables.filter(curTransactionsModelDao, new Predicate<PaymentTransactionModelDao>() {
            @Override
            public boolean apply(final PaymentTransactionModelDao curPaymentTransactionModelDao) {
                return curPaymentTransactionModelDao.getPaymentId().equals(curPaymentModelDao.getId());
            }
        });

        PaymentModelDao newPaymentModelDao = curPaymentModelDao;
        final Collection<PaymentTransaction> transactions = new LinkedList<PaymentTransaction>();
        for (final PaymentTransactionModelDao curPaymentTransactionModelDao : filteredTransactions) {
            PaymentTransactionModelDao newPaymentTransactionModelDao = curPaymentTransactionModelDao;

            final PaymentTransactionInfoPlugin paymentTransactionInfoPlugin = findPaymentTransactionInfoPlugin(newPaymentTransactionModelDao, pluginTransactions);
            if (paymentTransactionInfoPlugin != null) {
                // Make sure to invoke the Janitor task in case the plugin fixes its state on the fly
                // See https://github.com/killbill/killbill/issues/341
                final boolean hasChanged = incompletePaymentTransactionTask.updatePaymentAndTransactionIfNeededWithAccountLock(newPaymentModelDao, newPaymentTransactionModelDao, paymentTransactionInfoPlugin, internalTenantContext);
                if (hasChanged) {
                    newPaymentModelDao = paymentDao.getPayment(newPaymentModelDao.getId(), internalTenantContext);
                    newPaymentTransactionModelDao = paymentDao.getPaymentTransaction(newPaymentTransactionModelDao.getId(), internalTenantContext);
                }
            }

            final PaymentTransaction transaction = new DefaultPaymentTransaction(newPaymentTransactionModelDao.getId(),
                                                                                 newPaymentTransactionModelDao.getAttemptId(),
                                                                                 newPaymentTransactionModelDao.getTransactionExternalKey(),
                                                                                 newPaymentTransactionModelDao.getCreatedDate(),
                                                                                 newPaymentTransactionModelDao.getUpdatedDate(),
                                                                                 newPaymentTransactionModelDao.getPaymentId(),
                                                                                 newPaymentTransactionModelDao.getTransactionType(),
                                                                                 newPaymentTransactionModelDao.getEffectiveDate(),
                                                                                 newPaymentTransactionModelDao.getTransactionStatus(),
                                                                                 newPaymentTransactionModelDao.getAmount(),
                                                                                 newPaymentTransactionModelDao.getCurrency(),
                                                                                 newPaymentTransactionModelDao.getProcessedAmount(),
                                                                                 newPaymentTransactionModelDao.getProcessedCurrency(),
                                                                                 newPaymentTransactionModelDao.getGatewayErrorCode(),
                                                                                 newPaymentTransactionModelDao.getGatewayErrorMsg(),
                                                                                 paymentTransactionInfoPlugin);
            transactions.add(transaction);
        }

        final List<PaymentTransaction> sortedTransactions = perPaymentTransactionOrdering.immutableSortedCopy(transactions);
        return new DefaultPayment(curPaymentModelDao.getId(),
                                  curPaymentModelDao.getCreatedDate(),
                                  curPaymentModelDao.getUpdatedDate(),
                                  curPaymentModelDao.getAccountId(),
                                  curPaymentModelDao.getPaymentMethodId(),
                                  curPaymentModelDao.getPaymentNumber(),
                                  curPaymentModelDao.getExternalKey(),
                                  sortedTransactions,
                                  (withAttempts && !sortedTransactions.isEmpty()) ?
                                  getPaymentAttempts(sortedTransactions,
                                                     paymentDao.getPaymentAttempts(curPaymentModelDao.getExternalKey(), internalTenantContext),
                                                     internalTenantContext) : null
                                  );
    }

    private List<PaymentAttempt> getPaymentAttempts(final List<PaymentTransaction> purchasedTransactions,
                                                    final List<PaymentAttemptModelDao> pastPaymentAttempts,
                                                    final InternalTenantContext internalTenantContext) {

        List<PaymentAttempt> paymentAttempts = new ArrayList<PaymentAttempt>();

        // Last Attempt from model dao
        PaymentAttemptModelDao lastPaymentAttemptModelDao = pastPaymentAttempts.get(pastPaymentAttempts.size() - 1);
        // Last Attempt from Transactions
        PaymentTransaction lastPaymentTransaction = purchasedTransactions.get(purchasedTransactions.size() - 1);

        // Add Past Payment Attempts
        for (PaymentAttemptModelDao pastPaymentAttempt : pastPaymentAttempts) {
            DefaultPaymentAttempt paymentAttempt = new DefaultPaymentAttempt(
                    pastPaymentAttempt.getAccountId(),
                    pastPaymentAttempt.getPaymentMethodId(),
                    pastPaymentAttempt.getId(),
                    pastPaymentAttempt.getCreatedDate(),
                    pastPaymentAttempt.getUpdatedDate(),
                    pastPaymentAttempt.getCreatedDate(),
                    pastPaymentAttempt.getPaymentExternalKey(),
                    pastPaymentAttempt.getTransactionId(),
                    pastPaymentAttempt.getTransactionExternalKey(),
                    pastPaymentAttempt.getTransactionType(),
                    pastPaymentAttempt.getStateName(),
                    pastPaymentAttempt.getAmount(),
                    pastPaymentAttempt.getCurrency(),
                    pastPaymentAttempt.getPluginName(),
                    (lastPaymentTransaction.getPaymentInfoPlugin() != null) ? lastPaymentTransaction.getPaymentInfoPlugin().getProperties() : null);
            paymentAttempts.add(paymentAttempt);
        }

        // Get Future Payment Attempts from Notification Queue and add them to the list
        try {
            final NotificationQueue retryQueue = notificationQueueService.getNotificationQueue(DefaultPaymentService.SERVICE_NAME, DefaultRetryService.QUEUE_NAME);
            final List<NotificationEventWithMetadata<NotificationEvent>> notificationEventWithMetadatas =
                    retryQueue.getFutureNotificationForSearchKeys(internalTenantContext.getAccountRecordId(), internalTenantContext.getTenantRecordId());

            for (NotificationEventWithMetadata<NotificationEvent> notificationEvent : notificationEventWithMetadatas) {
                DefaultPaymentAttempt futurePaymentAttempt = new DefaultPaymentAttempt(
                        lastPaymentAttemptModelDao.getAccountId(), // accountId
                        lastPaymentAttemptModelDao.getPaymentMethodId(), // paymentMethodId
                        ((PaymentRetryNotificationKey) notificationEvent.getEvent()).getAttemptId(), // id
                        null, // createdDate
                        null, // updatedDate
                        notificationEvent.getEffectiveDate(), // effectiveDate
                        lastPaymentAttemptModelDao.getPaymentExternalKey(), // paymentExternalKey
                        null, // transactionId
                        lastPaymentAttemptModelDao.getTransactionExternalKey(), // transactionExternalKey
                        lastPaymentAttemptModelDao.getTransactionType(), // transactionType
                        SCHEDULED, // stateName
                        lastPaymentTransaction.getAmount(), // amount
                        lastPaymentTransaction.getCurrency(), // currency
                        ((PaymentRetryNotificationKey) notificationEvent.getEvent()).getPaymentControlPluginNames().get(0), // pluginName,
                        (lastPaymentTransaction.getPaymentInfoPlugin() != null) ? lastPaymentTransaction.getPaymentInfoPlugin().getProperties() : null); // pluginProperties
                paymentAttempts.add(futurePaymentAttempt);
            }
        } catch (NoSuchNotificationQueue noSuchNotificationQueue) {
            log.error("ERROR Loading Notification Queue - " + noSuchNotificationQueue.getMessage());
        }
        return paymentAttempts;
    }

    private PaymentTransactionInfoPlugin findPaymentTransactionInfoPlugin(final PaymentTransactionModelDao paymentTransactionModelDao, @Nullable final Iterable<PaymentTransactionInfoPlugin> pluginTransactions) {
        if (pluginTransactions == null) {
            return null;
        }

        return Iterables.tryFind(pluginTransactions,
                                 new Predicate<PaymentTransactionInfoPlugin>() {
                                     @Override
                                     public boolean apply(final PaymentTransactionInfoPlugin paymentTransactionInfoPlugin) {
                                         return paymentTransactionModelDao.getId().equals(paymentTransactionInfoPlugin.getKbTransactionPaymentId());
                                     }
                                 }).orNull();
    }

    private InternalTenantContext getInternalTenantContextWithAccountRecordId(final UUID accountId, final InternalTenantContext tenantContext) {
        final InternalTenantContext tenantContextWithAccountRecordId;
        if (tenantContext.getAccountRecordId() == null) {
            tenantContextWithAccountRecordId = internalCallContextFactory.createInternalTenantContext(accountId, tenantContext);
        } else {
            tenantContextWithAccountRecordId = tenantContext;
        }
        return tenantContextWithAccountRecordId;
    }
}
