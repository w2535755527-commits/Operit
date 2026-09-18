//
// Copyright(c) 2016-2017 benikabocha.
// Distributed under the MIT License (http://opensource.org/licenses/MIT)
//

#include "GLMMDModel.h"
#include <Saba/GL/GLVertexUtil.h>
#include <Saba/GL/GLTextureUtil.h>
#include <Saba/Base/Log.h>
#include <Saba/Base/Time.h>

#include <string>
#include <map>
#include <memory>
#include <cmath>
#include <cstdlib>
#include <glm/gtc/quaternion.hpp>

namespace saba
{
	GLMMDModel::GLMMDModel()
		: m_animTime(0)
		, m_indexType(0)
		, m_indexTypeSize(0)
		, m_enablePhysics(true)
		, m_enableEdge(true)
		, m_enableGroundShadow(true)
	{
		m_perfInfo.Clear();
	}

	GLMMDModel::~GLMMDModel()
	{
	}

	namespace
	{
		using TextureManager = std::map<std::string, GLTextureRef>;
		GLTextureRef CreateMMDTexture(
			TextureManager& texMan,
			const std::string& filename,
			bool genMipmap = true,
			bool rgba = false
		)
		{
			std::string key = filename + ":" +
				std::to_string(genMipmap) + ":" +
				std::to_string(rgba);
			auto findIt = texMan.find(key);
			if (findIt != texMan.end())
			{
				return (*findIt).second;
			}
			else
			{
				auto tex = CreateTextureFromFile(filename.c_str(), genMipmap, rgba);
				GLTextureRef texRef = std::move(tex);
				texMan.emplace(std::make_pair(key, texRef));
				return texRef;
			}
		}
	}

	bool GLMMDModel::Create(std::shared_ptr<MMDModel> mmdModel)
	{
		Destroy();

		size_t vtxCount = mmdModel->GetVertexCount();
		auto positions = mmdModel->GetPositions();
		auto normals = mmdModel->GetNormals();
		auto uvs = mmdModel->GetUVs();
		m_posVBO = CreateVBO(positions, vtxCount, GL_DYNAMIC_DRAW);
		m_norVBO = CreateVBO(normals, vtxCount, GL_DYNAMIC_DRAW);
		m_uvVBO = CreateVBO(uvs, vtxCount, GL_DYNAMIC_DRAW);

		m_posBinder = MakeVertexBinder<glm::vec3>();
		m_norBinder = MakeVertexBinder<glm::vec3>();
		m_uvBinder = MakeVertexBinder<glm::vec2>();

		const void* iboBuf = mmdModel->GetIndices();
		size_t indexCount = mmdModel->GetIndexCount();
		size_t indexElemSize = mmdModel->GetIndexElementSize();
		switch (indexElemSize)
		{
		case 1:
			m_ibo = CreateIBO((uint8_t*)iboBuf, indexCount, GL_STATIC_DRAW);
			m_indexType = GL_UNSIGNED_BYTE;
			m_indexTypeSize = 1;
			break;
		case 2:
			m_ibo = CreateIBO((uint16_t*)iboBuf, indexCount, GL_STATIC_DRAW);
			m_indexType = GL_UNSIGNED_SHORT;
			m_indexTypeSize = 2;
			break;
		case 4:
			m_ibo = CreateIBO((uint32_t*)iboBuf, indexCount, GL_STATIC_DRAW);
			m_indexType = GL_UNSIGNED_INT;
			m_indexTypeSize = 4;
			break;
		default:
			SABA_ERROR("Unknown Index Size. [{}]", indexElemSize);
			return false;
		}

		// Material
		size_t matCount = mmdModel->GetMaterialCount();
		auto materials = mmdModel->GetMaterials();
		m_materials.resize(matCount);
		TextureManager texMan;
		for (size_t matIdx = 0; matIdx < matCount; matIdx++)
		{
			auto& dest = m_materials[matIdx];
			const auto& src = materials[matIdx];

			dest.m_diffuse = src.m_diffuse;
			dest.m_alpha = src.m_alpha;
			dest.m_specularPower = src.m_specularPower;
			dest.m_specular = src.m_specular;
			dest.m_ambient = src.m_ambient;
			dest.m_edgeFlag = src.m_edgeFlag != 0;
			dest.m_edgeSize = src.m_edgeSize;
			dest.m_edgeColor = src.m_edgeColor;
			if (!src.m_texture.empty())
			{
				dest.m_texture = CreateMMDTexture(texMan, src.m_texture, true, true);
				dest.m_textureHaveAlpha = IsAlphaTexture(dest.m_texture);
			}
			dest.m_textureMulFactor = src.m_textureMulFactor;
			dest.m_textureAddFactor = src.m_textureAddFactor;

			if (!src.m_spTexture.empty())
			{
				dest.m_spTexture = CreateMMDTexture(texMan, src.m_spTexture, false, true);
			}
			dest.m_spTextureMode = src.m_spTextureMode;
			dest.m_spTextureMulFactor = src.m_spTextureMulFactor;
			dest.m_spTextureAddFactor = src.m_spTextureAddFactor;

			if (!src.m_toonTexture.empty())
			{
				dest.m_toonTexture = CreateMMDTexture(texMan, src.m_toonTexture);
			}
			dest.m_toonTextureMulFactor = src.m_toonTextureMulFactor;
			dest.m_toonTextureAddFactor = src.m_toonTextureAddFactor;

			dest.m_bothFace = src.m_bothFace;
			dest.m_groundShadow = src.m_groundShadow;
			dest.m_shadowCaster = src.m_shadowCaster;
			dest.m_shadowReceiver = src.m_shadowReceiver;
		}

		// SubMesh
		size_t subMeshCount = mmdModel->GetSubMeshCount();
		auto subMeshes = mmdModel->GetSubMeshes();
		m_subMeshes.resize(subMeshCount);
		for (size_t subMeshIdx = 0; subMeshIdx < subMeshCount; subMeshIdx++)
		{
			auto& dest = m_subMeshes[subMeshIdx];
			const auto& src = subMeshes[subMeshIdx];

			dest.m_beginIndex = src.m_beginIndex;
			dest.m_vertexCount = src.m_vertexCount;
			dest.m_materialID = src.m_materialID;
		}

		m_mmdModel = mmdModel;

		return true;
	}

	void GLMMDModel::Destroy()
	{
		m_vmdAnim.reset();
		m_mmdModel.reset();
		m_materials.clear();
		m_subMeshes.clear();
		m_indexType = 0;
		m_indexTypeSize = 0;

		m_posVBO.Destroy();
		m_norVBO.Destroy();
		m_uvVBO.Destroy();
		m_ibo.Destroy();
	}

	bool GLMMDModel::LoadAnimation(const VMDFile& vmd)
	{
		if (m_mmdModel == nullptr)
		{
			SABA_WARN("Loda Animation Fail. model is null");
			return false;
		}

		if (m_vmdAnim == nullptr)
		{
			m_vmdAnim = std::make_unique<VMDAnimation>();
			if (!m_vmdAnim->Create(m_mmdModel))
			{
				m_vmdAnim.reset();
				return false;
			}
		}

		if (!m_vmdAnim->Add(vmd))
		{
			m_vmdAnim.reset();
			return false;
		}

		// Physicsを同期する
		m_vmdAnim->SyncPhysics(float(m_animTime * 30.0), 30);

		return true;
	}

	void GLMMDModel::LoadPose(const VPDFile & vpd, int frameCount)
	{
		if (m_mmdModel != nullptr)
		{
			m_mmdModel->LoadPose(vpd, frameCount);
		}
	}

	void GLMMDModel::ResetAnimation()
	{
		m_mmdModel->InitializeAnimation();
		if (m_vmdAnim != nullptr)
		{
			m_vmdAnim->SyncPhysics(float(m_animTime * 30.0));
		}
	}

	void GLMMDModel::ClearAnimation()
	{
		m_vmdAnim.reset();
		m_animTime = 0;
		m_mmdModel->InitializeAnimation();
	}

	void GLMMDModel::SetAnimationTime(double time)
	{
		m_animTime = time;
	}

	double GLMMDModel::GetAnimationTime() const
	{
		return m_animTime;
	}

	void GLMMDModel::EvaluateAnimation(double animTime)
	{
		if (m_vmdAnim != 0)
		{
			m_animTime = animTime;
			double frame = m_animTime * 30.0;
			m_vmdAnim->Evaluate((float)frame);
		}
	}

	namespace
	{
		class Perf
		{
		public:
			void Start()
			{
				m_startTime = GetTime();
			}

			void Stop()
			{
				m_perfTime += GetTime() - m_startTime;
			}

			double GetPerfTime() const
			{
				return m_perfTime;
			}

		private:
			double	m_perfTime = 0;
			double	m_startTime = 0;
		};
	}
	void GLMMDModel::UpdateAnimation(double animTime, double elapsed)
	{
		Perf setupAnimPerf;
		Perf updateMorphAnimPerf;
		Perf updateNodeAnimPerf;
		Perf updatePhysicsAnimPerf;

		// Begin animation
		setupAnimPerf.Start();
		m_mmdModel->BeginAnimation();
		setupAnimPerf.Stop();

		// Evaluate VMD aniamtion (update morph and node parameter)
		setupAnimPerf.Start();
		EvaluateAnimation(animTime);
		setupAnimPerf.Stop();

		// Operit patch: apply morph overrides + auto blink before morph update
		ApplyMorphOverrides();
		UpdateAutoBlink(elapsed);
		// Update morph animation
		updateMorphAnimPerf.Start();
		m_mmdModel->UpdateMorphAnimation();
		updateMorphAnimPerf.Stop();

		// Update node animation (before physics animation)
		updateNodeAnimPerf.Start();
		m_mmdModel->UpdateNodeAnimation(false);
		updateNodeAnimPerf.Stop();;

		if (m_enablePhysics)
		{
			// Update physics animation
			updatePhysicsAnimPerf.Start();
			m_mmdModel->UpdatePhysicsAnimation((float)elapsed);
			updatePhysicsAnimPerf.Stop();
		}

		// Update node animation (after physics animation)
		updateNodeAnimPerf.Start();
		m_mmdModel->UpdateNodeAnimation(true);
		updateNodeAnimPerf.Stop();

		// Operit patch: apply look-at head rotation after node transforms
		ApplyLookAtOverride(elapsed);

		// End animation
		setupAnimPerf.Start();
		m_mmdModel->EndAnimation();
		setupAnimPerf.Stop();

		m_perfInfo.m_setupAnimTime = setupAnimPerf.GetPerfTime();
		m_perfInfo.m_updateMorphAnimTime = updateMorphAnimPerf.GetPerfTime();
		m_perfInfo.m_updateNodeAnimTime = updateNodeAnimPerf.GetPerfTime();
		m_perfInfo.m_updatePhysicsAnimTime = updatePhysicsAnimPerf.GetPerfTime();
	}

	void GLMMDModel::UpdateAnimationIgnoreVMD(double elapsed)
	{
		Perf setupAnimPerf;
		Perf updateMorphAnimPerf;
		Perf updateNodeAnimPerf;
		Perf updatePhysicsAnimPerf;

		// Save animation (save node TRS)
		setupAnimPerf.Start();
		m_mmdModel->SaveBaseAnimation();
		setupAnimPerf.Stop();

		// Begin animation (initialize node TRS)
		setupAnimPerf.Start();
		m_mmdModel->BeginAnimation();
		setupAnimPerf.Stop();

		// Load animation (load node TRS)
		setupAnimPerf.Start();
		m_mmdModel->LoadBaseAnimation();
		setupAnimPerf.Stop();

		// Operit patch: apply morph overrides + auto blink before morph update
		ApplyMorphOverrides();
		UpdateAutoBlink(elapsed);
		// Update morph animation
		updateMorphAnimPerf.Start();
		m_mmdModel->UpdateMorphAnimation();
		updateMorphAnimPerf.Stop();

		// Update node animation (before physics animation)
		updateNodeAnimPerf.Start();
		m_mmdModel->UpdateNodeAnimation(false);
		updateNodeAnimPerf.Stop();

		if (m_enablePhysics)
		{
			// Update physics animation
			updatePhysicsAnimPerf.Start();
			m_mmdModel->UpdatePhysicsAnimation((float)elapsed);
			updatePhysicsAnimPerf.Stop();
		}

		// Update node animation (after physics animation)
		updateNodeAnimPerf.Start();
		m_mmdModel->UpdateNodeAnimation(true);
		updateNodeAnimPerf.Stop();

		// Operit patch: apply look-at head rotation after node transforms
		ApplyLookAtOverride(elapsed);

		// End animation
		setupAnimPerf.Start();
		m_mmdModel->EndAnimation();
		setupAnimPerf.Stop();

		m_perfInfo.m_setupAnimTime = setupAnimPerf.GetPerfTime();
		m_perfInfo.m_updateMorphAnimTime = updateMorphAnimPerf.GetPerfTime();
		m_perfInfo.m_updateNodeAnimTime = updateNodeAnimPerf.GetPerfTime();
		m_perfInfo.m_updatePhysicsAnimTime = updatePhysicsAnimPerf.GetPerfTime();
	}

	void GLMMDModel::UpdateMorph()
	{
		m_mmdModel->SaveBaseAnimation();

		m_mmdModel->BeginAnimation();

		m_mmdModel->LoadBaseAnimation();

		m_mmdModel->UpdateMorphAnimation();
		m_mmdModel->UpdateNodeAnimation(false);
		m_mmdModel->UpdatePhysicsAnimation(0.0f);
		m_mmdModel->UpdateNodeAnimation(true);

		m_mmdModel->EndAnimation();
	}

	void GLMMDModel::Update()
	{
		if (m_mmdModel == nullptr)
		{
			return;
		}

		Perf updateModelPerf;
		Perf updateGLBufferPerf;

		updateModelPerf.Start();
		m_mmdModel->Update();

		size_t matCount = m_mmdModel->GetMaterialCount();
		for (size_t mi = 0; mi < matCount; mi++)
		{
			const auto& mmdMat = m_mmdModel->GetMaterials()[mi];
			m_materials[mi].m_diffuse = mmdMat.m_diffuse;
			m_materials[mi].m_alpha = mmdMat.m_alpha;
			m_materials[mi].m_specular = mmdMat.m_specular;
			m_materials[mi].m_specularPower = mmdMat.m_specularPower;
			m_materials[mi].m_ambient = mmdMat.m_ambient;
			m_materials[mi].m_textureMulFactor = mmdMat.m_textureMulFactor;
			m_materials[mi].m_textureAddFactor = mmdMat.m_textureAddFactor;
			m_materials[mi].m_spTextureMulFactor = mmdMat.m_spTextureMulFactor;
			m_materials[mi].m_spTextureAddFactor = mmdMat.m_spTextureAddFactor;
			m_materials[mi].m_toonTextureMulFactor = mmdMat.m_toonTextureMulFactor;
			m_materials[mi].m_toonTextureAddFactor = mmdMat.m_toonTextureAddFactor;
		}
		updateModelPerf.Stop();

		updateGLBufferPerf.Start();
		size_t vtxCount = m_mmdModel->GetVertexCount();
		UpdateVBO(m_posVBO, m_mmdModel->GetUpdatePositions(), vtxCount);
		UpdateVBO(m_norVBO, m_mmdModel->GetUpdateNormals(), vtxCount);
		UpdateVBO(m_uvVBO, m_mmdModel->GetUpdateUVs(), vtxCount);
		updateGLBufferPerf.Stop();

		m_perfInfo.m_updateModelTime = updateModelPerf.GetPerfTime();
		m_perfInfo.m_updateGLBufferTime = updateGLBufferPerf.GetPerfTime();
	}

	void GLMMDModel::PerfInfo::Clear()
	{
		m_setupAnimTime = 0;
		m_updateMorphAnimTime = 0;
		m_updateNodeAnimTime = 0;
		m_updatePhysicsAnimTime = 0;

		m_updateModelTime = 0;
		m_updateGLBufferTime = 0;
	}

	double GLMMDModel::PerfInfo::GetUpdateTime() const
	{
		return m_setupAnimTime
			+ m_updateMorphAnimTime
			+ m_updateNodeAnimTime
			+ m_updatePhysicsAnimTime
			+ m_updateModelTime
			+ m_updateGLBufferTime;
	}

	// === Operit patch: expressive control implementations ===
	namespace
	{
		const float kOperitPi = 3.14159265358979323846f;
		float OpDeg2Rad(float deg) { return deg * kOperitPi / 180.0f; }
	}

	void GLMMDModel::SetMorphOverride(const std::string& name, float weight)
	{
		m_morphOverrides[name] = weight;
	}

	void GLMMDModel::ClearMorphOverride(const std::string& name)
	{
		m_morphOverrides.erase(name);
	}

	void GLMMDModel::ClearAllMorphOverrides()
	{
		m_morphOverrides.clear();
	}

	void GLMMDModel::ApplyMorphOverrides()
	{
		if (m_mmdModel == nullptr || m_morphOverrides.empty())
		{
			return;
		}
		auto* morphMan = m_mmdModel->GetMorphManager();
		if (morphMan == nullptr)
		{
			return;
		}
		for (const auto& kv : m_morphOverrides)
		{
			auto* morph = morphMan->GetMorph(kv.first);
			if (morph != nullptr)
			{
				morph->SetWeight(kv.second);
			}
		}
	}

	void GLMMDModel::UpdateAutoBlink(double elapsed)
	{
		m_idleClock += elapsed;

		if (!m_autoBlinkEnabled || m_mmdModel == nullptr)
		{
			return;
		}

		auto* morphMan = m_mmdModel->GetMorphManager();
		if (morphMan == nullptr)
		{
			return;
		}

		MMDMorph* blink = morphMan->GetMorph(std::string("まばたき"));
		if (blink == nullptr) { blink = morphMan->GetMorph(std::string("blink")); }
		if (blink == nullptr) { blink = morphMan->GetMorph(std::string("Blink")); }
		if (blink == nullptr) { blink = morphMan->GetMorph(std::string("BLINK")); }
		if (blink == nullptr) { blink = morphMan->GetMorph(std::string("目パチ")); }
		if (blink == nullptr) { blink = morphMan->GetMorph(std::string("眨眼")); }
		if (blink == nullptr)
		{
			return;
		}

		m_blinkSinceLast += elapsed;

		float weight = 0.0f;
		if (m_blinkSinceLast >= m_blinkInterval)
		{
			const double t = m_blinkSinceLast - m_blinkInterval;
			if (t < 0.06)
			{
				weight = (float)(t / 0.06);
			}
			else if (t < 0.18)
			{
				weight = (float)((0.18 - t) / 0.12);
			}
			else
			{
				m_blinkSinceLast = 0.0;
				m_blinkInterval = 2.6 + (double)(std::rand() % 140) / 100.0;
			}
			if (weight < 0.0f) { weight = 0.0f; }
			if (weight > 1.0f) { weight = 1.0f; }
		}

		blink->SetWeight(weight);
	}

	void GLMMDModel::ApplyLookAtOverride(double elapsed)
	{
		if (m_mmdModel == nullptr)
		{
			return;
		}

		float yaw = 0.0f;
		float pitch = 0.0f;

		if (m_lookAtX != 0.0f || m_lookAtY != 0.0f)
		{
			yaw = m_lookAtX * 28.0f;
			pitch = m_lookAtY * 18.0f;
			m_gazeCurYaw = yaw;
			m_gazeCurPitch = pitch;
		}
		else if (m_autoGlanceEnabled)
		{
			// Operit patch: random head swing (wander gaze)
			m_gazeTimer -= elapsed;
			if (m_gazeTimer <= 0.0)
			{
				if ((std::rand() % 100) < 26)
				{
					m_gazeTargetYaw = 0.0f;
					m_gazeTargetPitch = 0.0f;
				}
				else
				{
					m_gazeTargetYaw =
						((float)(std::rand() % 2001) / 1000.0f - 1.0f) * 26.0f;
					m_gazeTargetPitch =
						((float)(std::rand() % 2001) / 1000.0f - 1.0f) * 13.0f;
				}
				m_gazeTimer = 1.3 + (double)(std::rand() % 240) / 100.0;
			}
			double blend = elapsed * 3.2;
			if (blend > 1.0) { blend = 1.0; }
			m_gazeCurYaw += (float)((m_gazeTargetYaw - m_gazeCurYaw) * blend);
			m_gazeCurPitch += (float)((m_gazeTargetPitch - m_gazeCurPitch) * blend);
			yaw = m_gazeCurYaw;
			pitch = m_gazeCurPitch;
		}

		if (yaw == 0.0f && pitch == 0.0f)
		{
			return;
		}

		auto* nodeMan = m_mmdModel->GetNodeManager();
		if (nodeMan == nullptr)
		{
			return;
		}

		MMDNode* head = nodeMan->GetMMDNode(std::string("頭"));
		if (head == nullptr) { head = nodeMan->GetMMDNode(std::string("head")); }
		if (head == nullptr) { head = nodeMan->GetMMDNode(std::string("Head")); }
		if (head == nullptr) { head = nodeMan->GetMMDNode(std::string("HEAD")); }
		if (head == nullptr) { head = nodeMan->GetMMDNode(std::string("头")); }
		if (head == nullptr)
		{
			return;
		}

		const glm::quat delta =
			glm::angleAxis(OpDeg2Rad(yaw), glm::vec3(0.0f, 1.0f, 0.0f)) *
			glm::angleAxis(OpDeg2Rad(pitch), glm::vec3(1.0f, 0.0f, 0.0f));

		head->SetRotate(head->GetInitialRotate() * delta);
		head->UpdateLocalTransform();
		head->UpdateGlobalTransform();
	}

}
