package saturn.backend

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import org.chipsalliance.cde.config._
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import saturn.common._
import saturn.insns._

class ExecuteSequencerIO(maxDepth: Int, nFUs: Int)(implicit p: Parameters) extends SequencerIO(new ExecuteMicroOp(nFUs)) {
  val rvs1 = Decoupled(new VectorReadReq)
  val rvs2 = Decoupled(new VectorReadReq)
  val rvd  = Decoupled(new VectorReadReq)
  val rvm  = Decoupled(new VectorReadReq)
  val pipe_write_req  = new VectorPipeWriteReqIO(maxDepth)
  val perm = new Bundle {
    val req = Decoupled(new CompactorReq(dLenB))
    val data = Input(UInt(dLen.W))
  }

  val acc_valid = Input(Bool())
  val acc_ready = Output(Bool())
}

class ExecuteSequencer(supported_insns: Seq[VectorInstruction], maxPipeDepth: Int, nFUs: Int)(implicit p: Parameters) extends Sequencer[ExecuteMicroOp]()(p) {
  def usesPerm = supported_insns.count(_.props.contains(UsesPermuteSeq.Y)) > 0
  def usesAcc = supported_insns.count(_.props.contains(Reduction.Y)) > 0
  def usesRvd = supported_insns.count(_.props.contains(ReadsVD.Y)) > 0
  def usesCompress = supported_insns.count(_.props.contains(F6(OPMFunct6.compress))) > 0

  def accepts(inst: VectorIssueInst) = !inst.vmu && new VectorDecoder(inst.funct3, inst.funct6, inst.rs1, inst.rs2, supported_insns, Nil).matched

  val io = IO(new ExecuteSequencerIO(maxPipeDepth, nFUs))

  val valid = RegInit(false.B)
  val inst  = Reg(new BackendIssueInst)
  val head  = Reg(Bool())
  val wvd_mask  = Reg(UInt(egsTotal.W))
  val rvs1_mask = Reg(UInt(egsTotal.W))
  val rvs2_mask = Reg(UInt(egsTotal.W))
  val rvd_mask  = Reg(UInt(egsTotal.W))
  val rvm_mask  = Reg(UInt(egsPerVReg.W))
  val slide     = Reg(Bool())
  val slide_up  = Reg(Bool())
  val slide1    = Reg(Bool())
  val slide_offset = Reg(UInt((1+log2Ceil(maxVLMax)).W))
  val perm_head = Reg(UInt(dLenOffBits.W))
  val perm_tail = Reg(UInt(dLenOffBits.W))
  val sets_wmask = Reg(Bool())
  val uses_perm = Reg(Bool())
  val elementwise = Reg(Bool())
  val narrowing_ext = Reg(Bool())
  val zext_imm5 = Reg(Bool())
  val pipelined = Reg(Bool())
  val pipe_stages = Reg(UInt(log2Ceil(maxPipeDepth).W))
  val fu_sel = Reg(UInt(nFUs.W))

  val acc_fold  = Reg(Bool())
  val acc_fold_id = Reg(UInt(log2Ceil(dLenB).W))

  val mvnrr    = inst.funct3 === OPIVI && inst.opif6 === OPIFunct6.mvnrr
  val rgatherei16 = inst.funct3 === OPIVV && inst.opif6 === OPIFunct6.rgatherei16 && usesPerm.B
  val compress = inst.opmf6 === OPMFunct6.compress && usesCompress.B
  val vs1_eew  = Mux(rgatherei16, 1.U, inst.vconfig.vtype.vsew + Mux(inst.reduction && inst.wide_vd, 1.U, 0.U))
  val vs2_eew  = inst.vconfig.vtype.vsew + inst.wide_vs2 - Mux(narrowing_ext, ~inst.rs1(2,1) + 1.U, 0.U)
  val vs3_eew  = inst.vconfig.vtype.vsew + inst.wide_vd
  val vd_eew   = inst.vconfig.vtype.vsew + inst.wide_vd
  val incr_eew = Seq(
    Mux(inst.renv1, vs1_eew, 0.U),
    Mux(inst.renv2, vs2_eew, 0.U),
    Mux(inst.renvd, vs3_eew, 0.U),
    vd_eew).foldLeft(0.U(2.W)) { case (b, a) => Mux(a > b, a, b) }
  val acc_copy = (vd_eew === 3.U && (dLenB == 8).B) || elementwise
  val acc_last = acc_fold_id + 1.U === log2Ceil(dLenB).U - vd_eew || acc_copy
  val uscalar  = Mux(inst.funct3(2), inst.rs1_data, inst.imm5)
  val sscalar  = Mux(inst.funct3(2), inst.rs1_data, inst.imm5_sext)
  val rgather    = inst.opif6 === OPIFunct6.rgather && usesPerm.B
  val rgather_ix = rgather && inst.funct3.isOneOf(OPIVX, OPIVI)
  val rgather_v  = rgather && inst.funct3.isOneOf(OPIVV)
  val renv1    = inst.renv1 && !inst.reduction
  val renv2    = Mux(rgather_ix, head, inst.renv2) && (!inst.reduction || !acc_fold)
  val renvd    = inst.renvd && usesRvd.B
  val renvm    = inst.renvm
  val renacc   = inst.reduction && usesAcc.B

  val use_wmask = !inst.vm && sets_wmask
  val eidx      = Reg(UInt(log2Ceil(maxVLMax).W))
  val eff_vl    = Mux(mvnrr, ((vLen/8).U >> vd_eew) << inst.emul, Mux(inst.scalar_to_vd0, 1.U, inst.vconfig.vl))
  val increments_as_mask = (!inst.renv1 || inst.reads_vs1_mask) && (!inst.renv2 || inst.reads_vs2_mask) && (!inst.wvd || inst.writes_mask)
  val next_eidx = get_next_eidx(eff_vl, eidx, incr_eew, 0.U, increments_as_mask, elementwise)
  val eidx_tail = next_eidx === eff_vl
  val tail      = Mux(inst.reduction && usesAcc.B, acc_fold && acc_last, eidx_tail)

  io.dis.ready := (!valid || (tail && io.iss.fire)) && !io.dis_stall

  when (io.dis.fire) {
    val dis_inst = io.dis.bits

    val dis_ctrl = new VectorDecoder(dis_inst.funct3, dis_inst.funct6, dis_inst.rs1, dis_inst.rs2, supported_insns,
      Seq(SetsWMask, UsesPermuteSeq, Elementwise, UsesNarrowingSext, ZextImm5, PipelinedExecution, PipelineStagesMinus1, FUSel(nFUs)))
    valid := true.B
    inst := io.dis.bits
    assert(dis_inst.vstart === 0.U)
    eidx := 0.U

    val vd_arch_mask  = get_arch_mask(dis_inst.rd , dis_inst.emul +& dis_inst.wide_vd)
    val vs1_arch_mask = get_arch_mask(dis_inst.rs1, Mux(dis_inst.reads_vs1_mask, 0.U, dis_inst.emul))
    val vs2_arch_mask = get_arch_mask(dis_inst.rs2, Mux(dis_inst.reads_vs2_mask, 0.U, dis_inst.emul +& dis_inst.wide_vs2))

    wvd_mask    := Mux(dis_inst.wvd               , FillInterleaved(egsPerVReg, vd_arch_mask), 0.U)
    rvs1_mask   := Mux(dis_inst.renv1             , FillInterleaved(egsPerVReg, vs1_arch_mask), 0.U)
    rvs2_mask   := Mux(dis_inst.renv2             , FillInterleaved(egsPerVReg, vs2_arch_mask), 0.U)
    rvd_mask    := Mux(dis_inst.renvd && usesRvd.B, FillInterleaved(egsPerVReg, vd_arch_mask), 0.U)
    rvm_mask    := Mux(dis_inst.renvm             , ~(0.U(egsPerVReg.W)), 0.U)
    head        := true.B
    acc_fold    := false.B
    acc_fold_id := 0.U
    sets_wmask  := dis_ctrl.bool(SetsWMask)
    uses_perm   := dis_ctrl.bool(UsesPermuteSeq) && usesPerm.B
    elementwise := dis_ctrl.bool(Elementwise)
    narrowing_ext := dis_ctrl.bool(UsesNarrowingSext)
    zext_imm5   := dis_ctrl.bool(ZextImm5)
    pipelined   := dis_ctrl.bool(PipelinedExecution)
    pipe_stages := dis_ctrl.uint(PipelineStagesMinus1)
    fu_sel      := dis_ctrl.uint(FUSel(nFUs))

    val dis_slide = (dis_inst.funct6.isOneOf(OPIFunct6.slideup.litValue.U, OPIFunct6.slidedown.litValue.U)
      && dis_inst.funct3 =/= OPIVV) && usesPerm.B
    val dis_slide_up     = !dis_inst.funct6(0)
    val dis_vl           = dis_inst.vconfig.vl
    val dis_sew          = dis_inst.vconfig.vtype.vsew
    val dis_vlmax        = dis_inst.vconfig.vtype.vlMax
    val dis_next_eidx    = get_next_eidx(dis_vl, 0.U, dis_sew, 0.U, false.B, false.B)
    val dis_slide1       = !dis_inst.isOpi
    val dis_uscalar      = Mux(dis_inst.funct3(2), dis_inst.rs1_data, dis_inst.imm5)
    val dis_slide_offset = Mux(!dis_slide1, get_max_offset(dis_uscalar), 1.U)
    val dis_tail         = dis_next_eidx === dis_vl
    val dis_rgather_eew  = Mux(dis_inst.opif6 === OPIFunct6.rgatherei16, 1.U, dis_sew)
    slide        := dis_slide
    when (dis_slide) {
      slide_up     := dis_slide_up
      slide1       := dis_slide1
      slide_offset := dis_slide_offset
    }
    perm_head    := Mux(dis_slide && dis_slide_up,
      (dis_slide_offset << dis_sew)(dLenOffBits-1,0),
      0.U)
    perm_tail   := Mux(dis_slide,
      Mux(dis_slide_up,
        Mux(dis_tail, dis_vl << dis_sew, 0.U),
        (Mux(dis_next_eidx + dis_slide_offset <= dis_vlmax, dis_next_eidx, dis_vlmax - dis_slide_offset) << dis_sew)(dLenOffBits-1,0)
      ),
      1.U << dis_rgather_eew)
  } .elsewhen (io.iss.fire) {
    valid := !tail
    head := false.B
  }

  io.vat := inst.vat
  io.seq_hazard.valid := valid
  io.seq_hazard.bits.rintent := hazardMultiply(rvs1_mask | rvs2_mask | rvd_mask | rvm_mask)
  io.seq_hazard.bits.wintent := hazardMultiply(wvd_mask)
  io.seq_hazard.bits.vat := inst.vat

  val vs1_read_oh = Mux(renv1   , UIntToOH(io.rvs1.bits.eg), 0.U)
  val vs2_read_oh = Mux(renv2   , UIntToOH(io.rvs2.bits.eg), 0.U)
  val vd_read_oh  = Mux(renvd   , UIntToOH(io.rvd.bits.eg ), 0.U)
  val vm_read_oh  = Mux(renvm   , UIntToOH(io.rvm.bits.eg ), 0.U)
  val vd_write_oh = Mux(inst.wvd, UIntToOH(io.iss.bits.wvd_eg), 0.U)

  val raw_hazard = ((vs1_read_oh | vs2_read_oh | vd_read_oh | vm_read_oh) & io.older_writes) =/= 0.U
  val waw_hazard = (vd_write_oh & io.older_writes) =/= 0.U
  val war_hazard = (vd_write_oh & io.older_reads) =/= 0.U
  val data_hazard = raw_hazard || waw_hazard || war_hazard

  val rgather_eidx = get_max_offset(Mux(rgather_ix && rgather, uscalar, io.perm.data & eewBitMask(vs1_eew)))
  val rgather_zero = rgather_eidx >= inst.vconfig.vtype.vlMax
  val rvs2_eidx = Mux(rgather || rgatherei16, rgather_eidx, eidx)
  io.rvs1.bits.eg := getEgId(inst.rs1, eidx     , vs1_eew, inst.reads_vs1_mask)
  io.rvs2.bits.eg := getEgId(inst.rs2, rvs2_eidx, vs2_eew, inst.reads_vs2_mask)
  io.rvd.bits.eg  := getEgId(inst.rd , eidx     , vs3_eew, false.B)
  io.rvm.bits.eg  := getEgId(0.U     , eidx     , 0.U    , true.B)

  io.rvs1.valid := valid && renv1
  io.rvs2.valid := valid && renv2
  io.rvd.valid  := valid && renvd
  io.rvm.valid  := valid && renvm

  // Oldest read requests get priority
  val oldest = inst.vat === io.vat_head
  io.rvs1.bits.oldest := oldest
  io.rvs2.bits.oldest := oldest
  io.rvd.bits.oldest  := oldest
  io.rvm.bits.oldest  := oldest

  val exu_scheduler = Module(new PipeScheduler(1, maxPipeDepth))
  exu_scheduler.io.reqs(0).request := valid && pipelined
  exu_scheduler.io.reqs(0).fire := io.iss.fire
  exu_scheduler.io.reqs(0).depth := pipe_stages

  val wvd_eg = getEgId(inst.rd, Mux(inst.reduction, 0.U, eidx), vd_eew, inst.writes_mask)
  io.pipe_write_req.request := valid && pipelined && exu_scheduler.io.reqs(0).available
  io.pipe_write_req.bank_sel := (if (vrfBankBits == 0) 1.U else UIntToOH(wvd_eg(vrfBankBits-1,0)))
  io.pipe_write_req.pipe_depth := pipe_stages
  io.pipe_write_req.oldest := oldest
  io.pipe_write_req.fire := io.iss.fire

  when (compress) { // The destination is not known at this poit
    io.pipe_write_req.bank_sel := ~(0.U(vParams.vrfBanking.W))
  }

  val read_perm_buffer = uses_perm && (!slide || Mux(slide_up,
    next_eidx > slide_offset,
    eidx +& slide_offset < inst.vconfig.vtype.vlMax))

  io.perm.req.bits.head := (if (usesPerm) perm_head else 0.U)
  io.perm.req.bits.tail := (if (usesPerm) perm_tail else 0.U)

  val iss_valid = (valid &&
    !data_hazard &&
    !(renv1 && !io.rvs1.ready) &&
    !(renv2 && !io.rvs2.ready) &&
    !(renvd && !io.rvd.ready) &&
    !(renvm && !io.rvm.ready) &&
    !(read_perm_buffer && !io.perm.req.ready) &&
    !(pipelined && !io.pipe_write_req.available) &&
    !(pipelined && !exu_scheduler.io.reqs(0).available) &&
    !(renacc && !io.acc_valid)
  )
  io.perm.req.valid := iss_valid && read_perm_buffer && io.iss.ready && usesPerm.B
  io.iss.valid := iss_valid
  io.acc_ready := iss_valid && renacc && usesAcc.B

  io.iss.bits.rvs1_eew  := vs1_eew
  io.iss.bits.rvs2_eew  := vs2_eew
  io.iss.bits.rvd_eew   := vs3_eew
  io.iss.bits.vd_eew    := vd_eew
  io.iss.bits.eidx      := eidx
  io.iss.bits.vl        := inst.vconfig.vl
  io.iss.bits.wvd_eg    := wvd_eg
  io.iss.bits.rs1       := inst.rs1
  io.iss.bits.rs2       := inst.rs2
  io.iss.bits.rd        := inst.rd
  io.iss.bits.funct3    := inst.funct3
  io.iss.bits.funct6    := inst.funct6
  io.iss.bits.tail      := tail
  io.iss.bits.head      := head
  io.iss.bits.vat       := inst.vat
  io.iss.bits.vm        := inst.vm
  io.iss.bits.rm        := inst.rm
  io.iss.bits.iterative := !pipelined
  io.iss.bits.pipe_depth := pipe_stages
  io.iss.bits.fu_sel    := fu_sel
  io.iss.bits.debug_id  := inst.debug_id

  val dlen_mask = ~(0.U(dLenB.W))
  val head_mask = dlen_mask << (eidx << vd_eew)(dLenOffBits-1,0)
  val tail_mask = dlen_mask >> (0.U(dLenOffBits.W) - (next_eidx << vd_eew)(dLenOffBits-1,0))
  val slide1up_mask = Mux(head && !inst.isOpi, eewByteMask(vs2_eew), 0.U)
  val slideup_mask = Mux(slide && slide_up && eidx < slide_offset,
    Mux(next_eidx <= slide_offset, 0.U, dlen_mask << (slide_offset << vd_eew)(dLenOffBits-1,0)) | slide1up_mask,
    dlen_mask)
  val full_tail_mask = Mux(tail,
    ~(0.U(dLen.W)) >> (0.U(log2Ceil(dLen).W) - eff_vl(log2Ceil(dLen)-1,0)),
    ~(0.U(dLen.W))
  )

  io.iss.bits.use_wmask := use_wmask
  io.iss.bits.eidx_mask := head_mask & tail_mask & slideup_mask
  io.iss.bits.full_tail_mask := full_tail_mask

  val slide_down_byte_mask = Mux(slide && !slide_up && next_eidx +& slide_offset > inst.vconfig.vtype.vlMax,
    Mux(eidx +& slide_offset >= inst.vconfig.vtype.vlMax,
      0.U,
      ~(0.U(dLenB.W)) >> (0.U(dLenOffBits.W) - ((inst.vconfig.vtype.vlMax - slide_offset) << vs2_eew))(dLenOffBits-1,0)),
    ~(0.U(dLenB.W)))
  val slide_down_bit_mask = FillInterleaved(8, slide_down_byte_mask)
  io.iss.bits.use_slide_rvs2 := slide
  io.iss.bits.slide_data := io.perm.data & slide_down_bit_mask

  io.iss.bits.use_scalar_rvs1 := inst.funct3.isOneOf(OPIVI, OPIVX, OPMVX, OPFVF) || rgather_v || rgatherei16
  io.iss.bits.scalar := Mux(rgather_v || rgatherei16,
    rgather_eidx,
    Mux(zext_imm5, uscalar, sscalar))
  io.iss.bits.use_zero_rvs2 := rgather_zero && (rgather || rgatherei16)

  io.iss.bits.acc       := inst.reduction && usesAcc.B
  io.iss.bits.acc_copy  := acc_copy
  io.iss.bits.acc_fold  := acc_fold
  io.iss.bits.acc_fold_id := acc_fold_id
  io.iss.bits.acc_ew      := elementwise


  when (io.iss.fire && !tail) {
    if (vParams.enableChaining) {
      when (next_is_new_eg(eidx, next_eidx, vd_eew, inst.writes_mask) && !inst.reduction && !compress) {
        val wvd_clr_mask = UIntToOH(io.iss.bits.wvd_eg)
        wvd_mask  := wvd_mask  & ~wvd_clr_mask
      }
      when (next_is_new_eg(eidx, next_eidx, vs2_eew, inst.reads_vs2_mask) && !(inst.reduction && head) && !rgather_v && !rgatherei16) {
        rvs2_mask := rvs2_mask & ~UIntToOH(io.rvs2.bits.eg)
      }
      when (rgather_ix) {
        rvs2_mask := 0.U
      }
      when (next_is_new_eg(eidx, next_eidx, vs1_eew, inst.reads_vs1_mask)) {
        rvs1_mask := rvs1_mask & ~UIntToOH(io.rvs1.bits.eg)
      }
      when (next_is_new_eg(eidx, next_eidx, vs3_eew, false.B)) {
        rvd_mask  := rvd_mask  & ~UIntToOH(io.rvd.bits.eg)
      }
      when (next_is_new_eg(eidx, next_eidx, 0.U    , true.B)) {
        rvm_mask  := rvm_mask  & ~UIntToOH(io.rvm.bits.eg)
      }
    }

    eidx := next_eidx

    if (usesAcc) {
      when (eidx_tail) { acc_fold := true.B }
      when (acc_fold) { acc_fold_id := acc_fold_id + 1.U }
    }


    if (usesPerm) {
      when (uses_perm && slide) {
        val next_next_eidx = get_next_eidx(eff_vl, next_eidx, incr_eew, 0.U, increments_as_mask, elementwise)
        val next_tail = next_next_eidx === eff_vl
        perm_head := Mux(slide_up,
          Mux(next_eidx < slide_offset, (slide_offset << vs2_eew)(dLenOffBits-1,0), 0.U),
          next_eidx << vs2_eew)
        perm_tail := Mux(slide_up,
          Mux(next_tail, eff_vl << vs2_eew, 0.U),
          (Mux(next_next_eidx + slide_offset <= inst.vconfig.vtype.vlMax, next_next_eidx, inst.vconfig.vtype.vlMax - slide_offset) << vs2_eew)(dLenOffBits-1,0))
      }
    }
  }

  io.busy := valid
  io.head := head

  if (!usesRvd) { rvd_mask := 0.U }
}
