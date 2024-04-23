package dma

import DMAController.DMAConfig.DMAConfig
import DMAController.{controlNodeParams, readNodeParams, writeNodeParams}
import freechips.rocketchip.amba.axi4.{AXI4MasterParameters, AXI4MasterPortParameters, AXI4SlaveParameters, AXI4SlavePortParameters}
import freechips.rocketchip.amba.axi4stream.{AXI4StreamMasterParameters, AXI4StreamMasterPortParameters, AXI4StreamSlaveParameters}
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, TransferSizes}

/* TestChain Params */
case class DMAChainParams (
    reader: dmaParams,
    writer: dmaParams
)

// DMA configuration
object DMAParams {
  // DMA configuration
  val readConfig = new DMAConfig(
    busConfig = "AXI_AXIL_AXIS",
    addrWidth = 32,
    readDataWidth = 32,
    writeDataWidth = 32,
    readMaxBurst = 0,
    writeMaxBurst = 16,
    reader4KBarrier = false,
    writer4KBarrier = true,
    controlDataWidth = 32,
    controlAddrWidth = 32,
    controlRegCount = 16,
    fifoDepth = 512
  )
  val writeConfig = new DMAConfig(
    busConfig = "AXIS_AXIL_AXI",
    addrWidth = 32,
    readDataWidth = 32,
    writeDataWidth = 32,
    readMaxBurst = 0,
    writeMaxBurst = 16,
    reader4KBarrier = false,
    writer4KBarrier = true,
    controlDataWidth = 32,
    controlAddrWidth = 32,
    controlRegCount = 16,
    fifoDepth = 512
  )

  // Reader addresses
  val r_readAddress  = AddressSet(0x60000000, 0x0FFF)
  val r_writeAddress = AddressSet(0x60001000, 0x0FFF)
  val r_ctrlAddress  = AddressSet(0x60002000, 0x0FFF)

  // Writer addresses
  val w_readAddress  = AddressSet(0x60003000, 0x0FFF)
  val w_writeAddress = AddressSet(0x60004000, 0x0FFF)
  val w_ctrlAddress  = AddressSet(0x60005000, 0x0FFF)

  // Reader parameters
  val readParams = dmaParams(
    reader = readNodeParams(
      AXI4 = Some(Seq(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "reader_read_node",
          id = IdRange(0, 15)))))
      ),
      AXI4Stream = Some(AXI4StreamSlaveParameters())
    ),
    writer = writeNodeParams(
      AXI4 = Some(Seq(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "reader_write_node",
          id = IdRange(0, 15)))))
      ),
      AXI4Stream = Some(Seq(AXI4StreamMasterPortParameters(
        Seq(AXI4StreamMasterParameters(
          name = "reader_write_node",
          n = readConfig.writeDataWidth / 8))))
      )
    ),
    control = controlNodeParams(
      AXI4 = Some(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
          address = Seq(r_ctrlAddress),
          supportsRead = TransferSizes(1, readConfig.controlDataWidth / 8),
          supportsWrite = TransferSizes(1, readConfig.controlDataWidth / 8),
          interleavedId = Some(0))),
        beatBytes = readConfig.controlDataWidth / 8)))
    ),
    dmaConfig = readConfig
  )

  // Writer parameters
  val writeParams = dmaParams(
    reader = readNodeParams(
      AXI4 = Some(Seq(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "writer_read_node",
          id = IdRange(0, 1)))))
      ),
      AXI4Stream = Some(AXI4StreamSlaveParameters())
    ),
    writer = writeNodeParams(
      AXI4 = Some(Seq(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "writer_write_node",
          id = IdRange(0, 1)))))
      ),
      AXI4Stream = Some(Seq(AXI4StreamMasterPortParameters(
        Seq(AXI4StreamMasterParameters(
          name = "writer_write_node",
          n = writeConfig.writeDataWidth / 8))))
      )
    ),
    control = controlNodeParams(
      AXI4 = Some(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
          address       = Seq(w_ctrlAddress),
          supportsRead  = TransferSizes(1, writeConfig.controlDataWidth / 8),
          supportsWrite = TransferSizes(1, writeConfig.controlDataWidth / 8),
          interleavedId = Some(0))),
        beatBytes = writeConfig.controlDataWidth / 8)))
    ),
    dmaConfig = writeConfig
  )
}