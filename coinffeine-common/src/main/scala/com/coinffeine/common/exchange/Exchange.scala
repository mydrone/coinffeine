package com.coinffeine.common.exchange

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import com.coinffeine.common._
import com.coinffeine.common.bitcoin._
import com.coinffeine.common.paymentprocessor.PaymentProcessor

case class Exchange[C <: FiatCurrency](
  id: Exchange.Id,
  parameters: Exchange.Parameters[C],
  buyer: Exchange.PeerInfo[KeyPair],
  seller: Exchange.PeerInfo[KeyPair],
  broker: Exchange.BrokerInfo) {

  val amounts: Exchange.Amounts[C] =
    Exchange.Amounts(parameters.bitcoinAmount, parameters.fiatAmount, parameters.totalSteps)
}

object Exchange {

  case class Id(value: String) {
    override def toString = s"exchange:$value"
  }

  object Id {
    def random() = new Id(value = Random.nextString(12)) // TODO: use a crypto secure method
  }

  case class Parameters[C <: FiatCurrency](bitcoinAmount: BitcoinAmount,
                                           fiatAmount: CurrencyAmount[C],
                                           totalSteps: Exchange.TotalSteps,
                                           lockTime: Long,
                                           commitmentConfirmations: Int,
                                           resubmitRefundSignatureTimeout: FiniteDuration,
                                           refundSignatureAbortTimeout: FiniteDuration,
                                           network: Network)

  case class StepNumber(value: Int) {
    require(value >= 0, s"Step number must be positive or zero ($value given)")

    val count = value + 1

    override def toString = s"step number $value"

    def next: StepNumber = new StepNumber(value + 1)
  }

  object StepNumber {
    val First = new StepNumber(0)
  }

  case class PeerInfo[KeyPair](connection: PeerConnection,
                               paymentProcessorAccount: PaymentProcessor.AccountId,
                               bitcoinKey: KeyPair)

  case class BrokerInfo(connection: PeerConnection)

  case class TotalSteps(value: Int) {
    require(value >= 0, s"Total steps must be positive or zero ($value given)")

    val isPositive: Boolean = value > 0
    val lastStep: StepNumber = StepNumber(value - 1)

    override def toString = s"$value total steps"
    def toBigDecimal = BigDecimal(value)
    def isLastStep(step: StepNumber): Boolean = step == lastStep
  }

  case class Amounts[C <: FiatCurrency](bitcoinAmount: BitcoinAmount,
                                        fiatAmount: CurrencyAmount[C],
                                        totalSteps: Exchange.TotalSteps) {
    require(totalSteps.isPositive,
      s"exchange amounts must have positive total steps ($totalSteps given)")
    require(bitcoinAmount.isPositive,
      s"bitcoin amount must be positive ($bitcoinAmount given)")
    require(fiatAmount.isPositive,
      s"fiat amount must be positive ($fiatAmount given)")

    val stepBitcoinAmount: BitcoinAmount = bitcoinAmount / totalSteps.toBigDecimal
    val stepFiatAmount: CurrencyAmount[C] = fiatAmount / totalSteps.toBigDecimal
    val buyerDeposit: BitcoinAmount = stepBitcoinAmount * BigDecimal(2)
    val buyerRefund: BitcoinAmount = buyerDeposit - stepBitcoinAmount
    val sellerDeposit: BitcoinAmount = bitcoinAmount + stepBitcoinAmount
    val sellerRefund: BitcoinAmount = sellerDeposit - stepBitcoinAmount

    val buyerInitialBitcoinAmount: BitcoinAmount = buyerDeposit
    val sellerInitialBitcoinAmount: BitcoinAmount = bitcoinAmount + sellerDeposit

    def channelOutputForBuyerAfter(step: Exchange.StepNumber): BitcoinAmount = {
      val amountSplit = stepBitcoinAmount * step.count
      if (totalSteps.isLastStep(step)) amountSplit + buyerDeposit else amountSplit
    }

    def channelOutputForSellerAfter(step: Exchange.StepNumber): BitcoinAmount = {
      val amountSplit = bitcoinAmount - (stepBitcoinAmount * step.count)
      if (totalSteps.isLastStep(step)) amountSplit + sellerDeposit else amountSplit
    }
  }
}
